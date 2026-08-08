# Third-party research and licensing

Research was performed on 2026-08-08 with GitHub repository metadata and current default heads. These are behavioral/architecture references only unless explicitly stated otherwise. No donor source was copied into this MIT-derived fork.

| Repository | Observed license / head | Decision |
| --- | --- | --- |
| [wgtunnel/android](https://github.com/wgtunnel/android) | MIT; `d349aeef52fd83bfb755edc9637abfe049d280dd` base | Direct upstream base; preserve notices. |
| [Leadaxe/LxBox](https://github.com/Leadaxe/LxBox) | GPL-3.0; `d3e39bb0e7189a398c1ccbc0c4ca38dcf0095378` | Studied only as a high-level routing/UI reference; no code copied. |
| [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) | MIT metadata; `008b91bfe8c0e2daca0ab69061efd9ea1ad71bd2` | Current metadata/description did not match the expected routing-engine donor; not used or embedded. |
| [celzero/rethink-app](https://github.com/celzero/rethink-app) | Apache-2.0; `e55b992cb8d14661025e541e264b9cd064329307` | Diagnostics/DNS-observation ideas only; no code copied. |
| [pumbaX/awg-multi-script](https://github.com/pumbaX/awg-multi-script) | MIT; `52b78acb0ea31ed1dbe3291ab02a35a484e19b8a` | Operational/profile-health ideas only; no code copied. |
| [amnezia-vpn/amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) | MIT; `08d68cdae27762c3e07f36bbb12d2bad32f81926` | Authoritative AWG implementation/capability reference; no code copied. |
| [amnezia-vpn/amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) | Apache-2.0; `f82900455f1aceaa85658686dc2c5e32c2c42a73` | Capability/API behavior reference only; no code copied. |
| [amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client) | GPL-3.0; `dcf53b989e684a2e3e3f7f5c090001fb2def73b9` | GPL behavior study only; no code copied. |
| [Gruven/amneziawg-plus-android](https://github.com/Gruven/amneziawg-plus-android) | Apache-2.0, current GitHub search result | Additional Android implementation surveyed; not used as a donor. |
| [bropines/awg-wiresocks](https://github.com/bropines/awg-wiresocks) | MIT, current GitHub search result | Additional Android/proxy implementation surveyed; not used as a donor. |
| [hoaxisr/awg-manager](https://github.com/hoaxisr/awg-manager) | MIT, current GitHub search result | Additional management/configurator reference; not used as a donor. |
| [ks-tool/awg-admin](https://github.com/ks-tool/awg-admin) | Apache-2.0, current GitHub search result | Additional management/configurator reference; not used as a donor. |
| [Skiro1/warp-awg-gen](https://github.com/Skiro1/warp-awg-gen) | MIT, current GitHub search result | Generator behavior reference only; no code copied. |

SSH bootstrap was considered as a separate transport concern. No SSH dependency is added to the
APK: the shipped flow is a guided, operator-controlled server installer fallback followed by
HTTPS pairing. This keeps host-key verification, remote privilege boundaries and firewall changes
out of the Android product until a dedicated maintained transport and UX are reviewed.

Repositories with no declared/other/GPL/AGPL license from the search results were excluded as implementation donors. The Cat configurator contains independently written deterministic validation based on the existing upstream editor behavior and documented capability gating. It does not add unrelated network-evasion functionality.
