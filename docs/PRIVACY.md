# Privacy and local-network settings

Koky & Sosy Player stores providers, favourites, history and optional TMDB credentials in Android app-private storage. The application has no developer account login or project-owned telemetry.

**External endpoints:** your chosen provider; artwork endpoints returned by it; TMDB metadata/image servers when you enable cast/metadata; the GitHub release API to check the **Koky & Sosy** repository; and NAS/local sync endpoints only if you enable them.

**Phone management:** a short-lived QR-paired HTTP page is served by the TV over the *local* network. Every request must provide the temporary TV-generated token, and the server refuses non-private addresses. The `/tmdb` GET endpoint returns only two booleans (key configured, token configured); POST replaces the key/read token without ever returning stored values. **Local HTTP is not encrypted:** use a trusted network, do not share the QR URL, and close the manager session afterwards.

**Secret limitations:** API defaults bundled at build time can be recovered from an APK. Do not post API tokens, provider credentials or QR links to public issues, documentation or logs.

Factory reset destroys the app's saved local data. Device backups, external recordings or network shares can have separate retention controls. The app ships without IPTV channels or subscription credentials.
