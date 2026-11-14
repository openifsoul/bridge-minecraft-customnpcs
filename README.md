# IfSoul CustomNPCs-Unofficial bridge

Bridge for Minecraft Java 1.21.1, NeoForge 21.1.235,
JDK 21 and [CustomNPCs-Unofficial 1.21.1.20251230](https://www.curseforge.com/minecraft/mc-mods/customnpcs-unofficial/files/7411561).
The operator selects one player and one NPC. Right-click that NPC and use
`/ifsoul-customnpcs <text>` to send a private line.

A compatible NeoForge client and the unchanged server bridge JAR were used
to verify pairing, native approach, cancellation before movement, stop during
movement, grant revocation, and pairing again after restart without replay.
The client also received a private reply that was recorded in App. The test
used an isolated offline player and a deterministic provider.

## Optional game actions

Enable `game-actions=true` in the bridge configuration and issue an App pairing
grant with `movement` permission for this player and NPC. Existing dialogue-only
grants continue to provide private dialogue. The model picks from choices the
bridge supplies for the selected NPC.

Come to the selected player’s location using the mod’s native navigation.

The NPC must have no path already in progress. Use `/ifsoul-customnpcs stop`
or `/ifsoul-customnpcs wait` to cancel a pending action or stop movement started by the bridge. Movement also
ends on timeout, loss of the player or NPC, or loss of authorization.

The game host reports the result of the action to App.

During movement, the bridge normally re-checks the App grant once per
second. The service round trip adds to that interval. A short movement can
finish before the next check, and App rejects outcomes sent with a revoked
grant.

## Build

Use the official verified NeoForge 21.1.235 installer to prepare an isolated
server directory, without starting the game. Set `JAVA_HOME` to JDK 21,
`IFSOUL_NEOFORGE_ROOT` to that directory and `IFSOUL_NEOFORGE_UPSTREAM` to
a directory containing the separately downloaded upstream mod saved as
`customnpcs-20251230.jar`. Run `./check.sh` to verify inputs, compile the bridge and
test the device dialogue client. The artifact is `build/ifsoul-customnpcs.jar`.
The output contains original bridge code. Upstream jars and assets remain
separately installed.

## Configure

With the required upstream mods separately installed, write
`config/ifsoul-customnpcs.properties` in the server directory:

```properties
service-origin=http://127.0.0.1:8788
player-uuid=<selected player UUID reported by this server>
npc-id=<selected NPC entity UUID>
pairing-code=<fresh one-use IfSoul device code>
```

Protect the config directory. Use HTTPS for a remote service. The bearer
credential is held in process memory and expires with the service grant.
Renew the code after restart or grant expiration.

## Capabilities and scope

| Operation | Behavior |
| --- | --- |
| Bind | One service grant for the exact server-authoritative player and CustomNPCs-Unofficial NPC. |
| Interact | Right-click the selected NPC, then use `/ifsoul-customnpcs <text>` in the same world within eight blocks. The bridge checks the target again before replying. |
| Reply | Bounded private text to the approved player. Disconnect, world change and server shutdown invalidate pending delivery. |
| Game control | The optional movement grant permits one bounded approach to the selected player. The host owns navigation and game permissions. |

In IfSoul **Games → Configure Minecraft**, choose the matching server software
and bridge. Select the character, memory and provider and enter the player and
NPC identity before issuing a code. A Desktop user can choose **Install from
local JAR** and a stopped server directory for a matching pinned IfSoul JAR;
operators can also copy the JAR to the server's plugin or mod directory. Install
upstream dependencies separately. Enter the five-minute code in the bridge
configuration and start the server. Refresh status in Games to confirm the
handshake. Use **Revoke** to stop the service grant. If pairing fails, recheck the
exact profile, both IDs, service reachability and the unused code. For a native
server on another machine, use the service's reachable HTTPS origin.

The server bridge uses the App device API directly. SDK integrators can use
the [host integration guide](https://github.com/openifsoul/sdk/blob/main/docs/mineflayer-bridge.md)
for bounded host adapters. The Java bridge does not require the JavaScript SDK
at runtime.
