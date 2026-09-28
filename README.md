# Hot Potato

A round of hot potato on a live server. One player gets a ticking potato, passes it by hitting
someone else, and explodes if they are still holding it when the fuse runs out.

```
/hotpotato start <radius> [minSeconds] [maxSeconds]   pick a random player within radius
/hotpotato player <target> [minSeconds] [maxSeconds]  start on a specific player
/hotpotato stop
/hotpotato status
```

The fuse is rolled between `minSeconds` and `maxSeconds`, defaulting to 20 and 40, and **nobody is
told what it landed on**. The action bar shows a wiggling, pulsing warning that gets more urgent as
the timer drops, which is as much information as anyone gets.

## Passing it

Hit another player and the potato moves to them. There is a three-second grace period on the new
holder, so two people standing next to each other cannot ping-pong it back and forth as the timer
expires - whoever takes it has to live with it for a moment.

The potato cannot be dropped. Pressing Q and dragging it out of the inventory both go through the
same drop path, and a mixin there keeps it glued on. The bomb is tracked against the player rather
than the item anyway, so dropping it would never have helped - the mixin just stops the item
vanishing from the hotbar and confusing everyone.

## The explosion

When the fuse runs out the holder dies. The kill is forced directly rather than dealt as damage,
because a damage event can be cancelled - by spawn protection, a claim plugin, creative mode - and a
hot potato that fizzles in the safe zone is not a hot potato. It goes off wherever the holder is
standing.

## Server-side only - players install nothing

Vanilla items, vanilla commands, no new content.

## Requirements

Drop these into your **server's** `mods/` folder:

| Mod | Version |
| --- | --- |
| `hot-potato-0.1.0.jar` | this mod |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.155.2+26.2` (or compatible) |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `1.13.12+kotlin.2.4.0` (or compatible) |

Minecraft **26.2**, Fabric Loader **0.19.3+**, **Java 25**.

## Limits

One round at a time, and no config file - the fuse range is a command argument, everything else is
constant. The mixin targets `LivingEntity` rather than `Player`, because as of MC 1.21.9 the
three-argument `drop` lives there; a guard keeps it inert for everything that is not a player
holding the potato.
