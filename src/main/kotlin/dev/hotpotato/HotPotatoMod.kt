package dev.hotpotato

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Server-side Hot Potato. A random player in the radius gets a ticking potato, passes it by hitting
 * someone, and explodes if still holding it at zero. The explosion force-kills directly, bypassing
 * the damage event, so it still works inside a spawn-protection safe zone.
 */
object HotPotatoMod : DedicatedServerModInitializer {

    private val LOG = LoggerFactory.getLogger("hot-potato")
    private const val POTATO_NAME = "Hot Potato"

    // Default random fuse range (seconds) when the admin doesn't specify one.
    private const val DEFAULT_MIN = 20
    private const val DEFAULT_MAX = 40

    // After a pass, the player who gave it away is briefly immune to getting it straight back from
    // the new holder, which stops two players ping-ponging the potato. Measured in server ticks.
    private const val GRACE_TICKS = 3 * 20

    private var holder: UUID? = null
    private var secondsLeft = 0
    private var ticksToSecond = 20
    private var running = false
    private var anim = 0 // animation frame counter for the action bar (also our tick clock)

    // Anti-ping-pong grace: lastPasser is protected from holder until anim >= graceUntilAnim.
    private var lastPasser: UUID? = null
    private var graceUntilAnim = 0
    private var nextDenyMsgAnim = 0 // throttle the "can't pass back yet" notice

    override fun onInitializeServer() {
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { tick(it) })

        AttackEntityCallback.EVENT.register(AttackEntityCallback { player, world, _, entity, _ ->
            if (!running || world.isClientSide) return@AttackEntityCallback InteractionResult.PASS
            val attacker = player as? ServerPlayer ?: return@AttackEntityCallback InteractionResult.PASS
            if (attacker.uuid != holder) return@AttackEntityCallback InteractionResult.PASS
            val victim = entity as? ServerPlayer ?: return@AttackEntityCallback InteractionResult.PASS

            // Grace period: the player who just gave it away can't have it shoved straight back.
            if (victim.uuid == lastPasser && anim < graceUntilAnim) {
                if (anim >= nextDenyMsgAnim) {
                    val secs = (graceUntilAnim - anim + 19) / 20 // ceil to whole seconds
                    attacker.sendSystemMessage(
                        Fmt.legacy("&7Can't pass straight back to &e${victim.gameProfile.name}&7 yet - &c${secs}s&7."),
                    )
                    nextDenyMsgAnim = anim + 20
                }
                return@AttackEntityCallback InteractionResult.FAIL // eat the hit, no pass
            }

            pass(attacker, victim, world as ServerLevel)
            InteractionResult.FAIL // clean pass, so the hit damage is not dealt
        })

        registerCommand()
        LOG.info("Hot Potato ready.")
    }

    private fun registerCommand() {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
            dispatcher.register(
                literal("hotpotato")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(
                        literal("start").then(
                            argument("radius", IntegerArgumentType.integer(1))
                                // no range given -> default random fuse
                                .executes { start(it, IntegerArgumentType.getInteger(it, "radius"), DEFAULT_MIN, DEFAULT_MAX); 1 }
                                .then(
                                    argument("minSeconds", IntegerArgumentType.integer(1)).then(
                                        argument("maxSeconds", IntegerArgumentType.integer(1))
                                            .executes {
                                                start(
                                                    it,
                                                    IntegerArgumentType.getInteger(it, "radius"),
                                                    IntegerArgumentType.getInteger(it, "minSeconds"),
                                                    IntegerArgumentType.getInteger(it, "maxSeconds"),
                                                )
                                                1
                                            }
                                    )
                                )
                        )
                    )
                    .then(
                        literal("player").then(
                            argument("target", EntityArgument.player())
                                .executes { startOn(it, EntityArgument.getPlayer(it, "target"), DEFAULT_MIN, DEFAULT_MAX); 1 }
                                .then(
                                    argument("minSeconds", IntegerArgumentType.integer(1)).then(
                                        argument("maxSeconds", IntegerArgumentType.integer(1))
                                            .executes {
                                                startOn(
                                                    it,
                                                    EntityArgument.getPlayer(it, "target"),
                                                    IntegerArgumentType.getInteger(it, "minSeconds"),
                                                    IntegerArgumentType.getInteger(it, "maxSeconds"),
                                                )
                                                1
                                            }
                                    )
                                )
                        )
                    )
                    .then(literal("stop").executes { stop(it.source.server); reply(it.source, "Stopped."); 1 })
                    .then(literal("status").executes { status(it.source); 1 })
            )
        })
    }

    // --- event control ---

    private fun start(ctx: CommandContext<CommandSourceStack>, radius: Int, minSec: Int, maxSec: Int) {
        val src = ctx.source
        val level = src.level
        val origin = src.position
        val r2 = (radius * radius).toDouble()
        val candidates = level.players().filter { it.distanceToSqr(origin.x, origin.y, origin.z) <= r2 }
        if (candidates.isEmpty()) {
            reply(src, "&cNo players within $radius blocks of the command origin.")
            return
        }
        val chosen = candidates.random()
        val rolled = beginEvent(src.server, chosen, minSec, maxSec)
        // Surfaces the pool size so a tiny radius / console origin doesn't masquerade as "not random".
        reply(src, "&7Started on &f${chosen.gameProfile.name}&7 (random of ${candidates.size}): fuse ${minSec}-${maxSec}s (rolled ${rolled}s).")
    }

    private fun startOn(ctx: CommandContext<CommandSourceStack>, target: ServerPlayer, minSec: Int, maxSec: Int) {
        val src = ctx.source
        val rolled = beginEvent(src.server, target, minSec, maxSec)
        reply(src, "&7Started on &f${target.gameProfile.name}&7: fuse ${minSec}-${maxSec}s (rolled ${rolled}s).")
    }

    /** Clear any prior event, hand the potato to [chosen], roll the fuse, and announce. Returns the fuse. */
    private fun beginEvent(server: MinecraftServer, chosen: ServerPlayer, minSec: Int, maxSec: Int): Int {
        if (running) holder?.let { server.playerList.getPlayer(it) }?.let { removePotato(it) }
        reset()
        giveHolder(chosen)
        holder = chosen.uuid
        // Random hidden fuse, so nobody knows exactly when it blows.
        secondsLeft = randomInRange(minSec, maxSec)
        ticksToSecond = 20
        running = true
        broadcast(server, "&c&lHOT POTATO! &e${chosen.gameProfile.name}&7 has the potato - pass it before it blows!")
        return secondsLeft
    }

    private fun randomInRange(min: Int, max: Int): Int =
        if (max <= min) min else kotlin.random.Random.nextInt(min, max + 1)

    private fun stop(server: MinecraftServer) {
        if (running) {
            holder?.let { server.playerList.getPlayer(it) }?.let { removePotato(it) }
            broadcast(server, "&7Hot Potato event stopped.")
        }
        reset()
    }

    private fun status(src: CommandSourceStack) {
        if (!running) {
            reply(src, "No event running.")
            return
        }
        val name = holder?.let { src.server.playerList.getPlayer(it) }?.gameProfile?.name ?: "?"
        reply(src, "&eHolder: &f$name&7, &e${secondsLeft}s&7 left.")
    }

    // --- tick / explode / pass ---

    private fun tick(server: MinecraftServer) {
        if (!running) return
        anim++

        val player = holder?.let { server.playerList.getPlayer(it) }
        if (player == null) {
            broadcast(server, "&7Hot Potato holder left - event ended.")
            reset()
            return
        }

        // Can't be gotten rid of: if the potato isn't on them, put it straight back.
        ensurePotato(player)

        // Live action bar: wiggle and pulse, several times a second.
        if (anim % 2 == 0) animateActionBar(player)

        // 1 Hz countdown + escalating audio.
        if (--ticksToSecond > 0) return
        ticksToSecond = 20
        secondsLeft--
        if (secondsLeft <= 0) {
            explode(server, player)
            return
        }
        (player.level() as? ServerLevel)?.let { lvl ->
            if (secondsLeft <= 3) {
                lvl.playSound(null, player.x, player.y, player.z, SoundEvents.CREEPER_PRIMED, SoundSource.PLAYERS, 2.0f, 1.0f)
            } else {
                lvl.playSound(null, player.x, player.y, player.z, SoundEvents.NOTE_BLOCK_HAT, SoundSource.PLAYERS, 1.0f, 1.5f)
            }
        }
    }

    /** A "live" action bar: horizontal wiggle (leading spaces) + color/bold pulse. */
    private fun animateActionBar(player: ServerPlayer) {
        val danger = secondsLeft in 1..3
        val pad = " ".repeat(intArrayOf(0, 1, 2, 1)[(anim / 2) % 4]) // triangle-wave wiggle
        val bold = (anim / 4) % 2 == 0                                // pulse ~every 0.4s
        val color = when {
            danger && bold -> "&4&l"
            danger -> "&c&l"
            bold -> "&6&l"
            else -> "&e&l"
        }
        val label = if (danger) "ABOUT TO BLOW!" else "HOT POTATO - PASS IT!"
        player.sendOverlayMessage(Fmt.legacy("$pad$color$label"))
    }

    /** Guarantee the holder still has the potato; re-give it if it's gone (dropped/stashed). */
    private fun ensurePotato(player: ServerPlayer) {
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            if (isPotato(inv.getItem(i))) return
        }
        giveHolder(player)
    }

    private fun explode(server: MinecraftServer, player: ServerPlayer) {
        val level = player.level() as? ServerLevel
        if (level != null) {
            val x = player.x
            val y = player.y + 1.0
            val z = player.z
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 2, 0.3, 0.3, 0.3, 0.0)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 40, 0.6, 0.6, 0.6, 0.05)
            level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 4.0f, 1.0f)
        }
        removePotato(player)
        // Force the death directly, bypassing the damage event so spawn-protection cannot cancel it.
        player.setHealth(0f)
        player.die(player.damageSources().generic())
        broadcast(server, "&c&l${player.gameProfile.name} exploded!")
        reset()
    }

    private fun pass(attacker: ServerPlayer, victim: ServerPlayer, level: ServerLevel) {
        removePotato(attacker)
        giveHolder(victim)
        holder = victim.uuid
        // Protect the person who just gave it away from an instant return pass.
        lastPasser = attacker.uuid
        graceUntilAnim = anim + GRACE_TICKS
        nextDenyMsgAnim = anim
        broadcast(level.getServer(), "&e${attacker.gameProfile.name} &7passed the potato to &e${victim.gameProfile.name}&7!")
    }

    // --- item handling ---

    private fun potatoItem(): ItemStack {
        val stack = ItemStack(Items.POTATO)
        stack.set(DataComponents.CUSTOM_NAME, Fmt.legacy("&c&lHot Potato"))
        stack.set(
            DataComponents.LORE,
            ItemLore(listOf(Fmt.legacy("&7Pass it before it blows!"), Fmt.legacy("&8Hit another player to pass."))),
        )
        return stack
    }

    private fun giveHolder(player: ServerPlayer) {
        val current = player.getItemInHand(InteractionHand.MAIN_HAND)
        if (!current.isEmpty) player.inventory.add(current) // stash whatever they were holding
        player.setItemInHand(InteractionHand.MAIN_HAND, potatoItem())
    }

    private fun removePotato(player: ServerPlayer) {
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (!s.isEmpty && isPotato(s)) inv.setItem(i, ItemStack.EMPTY)
        }
    }

    private fun isPotato(stack: ItemStack): Boolean = isHotPotatoStack(stack)

    /** Public so the drop Mixin can identify the potato. */
    @JvmStatic
    fun isHotPotatoStack(stack: ItemStack): Boolean =
        stack.get(DataComponents.CUSTOM_NAME)?.string == POTATO_NAME

    // --- helpers ---

    private fun reset() {
        running = false
        holder = null
        secondsLeft = 0
        ticksToSecond = 20
        lastPasser = null
        graceUntilAnim = 0
        nextDenyMsgAnim = 0
    }

    private fun broadcast(server: MinecraftServer, legacy: String) {
        val msg = Fmt.legacy(legacy)
        server.playerList.players.forEach { it.sendSystemMessage(msg) }
    }

    private fun reply(src: CommandSourceStack, legacy: String) {
        src.sendSuccess({ Component.literal("[HotPotato] ").append(Fmt.legacy(legacy)) }, false)
    }
}
