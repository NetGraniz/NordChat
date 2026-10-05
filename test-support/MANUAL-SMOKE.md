# Ordinary-client smoke check — deferred by owner

The isolated fixture was stopped gracefully on 4 October 2026 when the owner
explicitly requested moving on to NordFilter and deploying the updates later.
No ordinary-client result was supplied. This check remains pending.
The stopped fixture had the following addresses on the computer hosting Codex:
127.0.0.1:25665 (Velocity) -> 25667 (NanoLimbo) -> 25666 (Paper 26.2-129).
All endpoints bind ONLY loopback. Proxy STATUS protocol 776 responds.
NordChat-0.1.3.jar matches release SHA256
013E8EDAA7438EB698060D53AE122088DDA75F5C2F10780EE5FBA4A6D78A7F09.
One preference-storage worker was verified. ChatTestProbe was moved outside the
plugin directory before startup; no artificial permission/filter bypass is active.
NordFilter remains unchanged, with synthetic test-only banword data.
No production preference/account/world data or forwarding secret is present.

Ask the owner to connect from that same computer with Minecraft 26.2, send several
distinct Latin-script chat messages, disconnect, reconnect and send another message.
Check the actual client for chat-validation errors and logs for routing/chat.
A successful ordinary-client check is not a full moderation/load verification.
Do not treat the automatic offline-client checks as proof of signed-chat
compatibility. Ordinary-client verification remains a pre-deployment follow-up.

The former runner session 68183 is no longer active. Paper/proxy were stopped
through the runner's console shutdown handler; only its isolated NanoLimbo was
terminated. Ports 25665/25666/25667 were verified closed. Production was untouched.
