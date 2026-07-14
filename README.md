<div align="center">
  <img src="app_icon_simple.png" width="128" height="128" alt="PureBrowse VPN Icon" />
  <h1>PureBrowse VPN</h1>
</div>

**PureBrowse VPN** is a robust, system-wide site blocker for Android. It operates as a local loopback VPN entirely on-device, intercepting DNS queries and instantly dropping connections to over 750,000+ domains from the [Bon-Appétit Blocklist](https://github.com/Bon-Appetit/porn-domains), without routing your traffic to external servers.

---

### ✨ Features
* **High-Performance Native Interceptor**: Uses a C++ packet parser bound via JNI to read IP/UDP headers directly from the Android `TUN` interface, ensuring zero battery drain and instant connection blocking.
* **Auto-Syncing Database**: A background `WorkManager` fetches the latest list from GitHub daily, parsing and updating the underlying SQLite Room database efficiently.
* **Manual Domain Blocks**: A custom user interface allows you to add specific domains to block (e.g., `reddit.com`) that persist completely independent of the daily auto-syncs.
* **Streak Accountability**: Disabling the VPN logs a "Protection Broken" count right on the home screen to act as psychological friction against evasion.
* **No Bypass Options**: No budgets, no timers, and no "allowed minutes". A domain is either blocked indefinitely or allowed.

---

### 🛠️ How to Build
This is a pure Native Android project (Kotlin / C++) targeting Android 12+. You do not need Android Studio to build it.

1. Push your code to the `main` branch.
2. Navigate to the **"Actions"** tab on GitHub.
3. Wait for the **"Build Android APK"** workflow to complete.
4. Scroll to the **Artifacts** section at the bottom of the successful run and download `purebrowse-vpn-debug.zip`.
5. Extract the `.zip`, transfer the `.apk` file to your Android phone, and install it!

---

### 🚀 How to Use
1. **Enable Protection**: Open the app and tap the blue **"Enable Protection"** button. Android will prompt you to grant the app VPN permission. Once granted, a key icon will appear in your status bar indicating all network traffic is being filtered.
2. **Adding Manual Blocks**: Scroll to the "Manual Domain Blocks" section. Type the bare domain (e.g., `twitter.com` — do not include `https://` or paths) and tap **Add**. It is instantly blocked system-wide.
3. **Removing Blocks**: Tap any domain listed under your manual blocks to instantly remove it.
4. **Always-On VPN (Recommended)**: To prevent Android from ever shutting down the VPN in the background, go to your phone's **Settings → Network & Internet → VPN → PureBrowse VPN (Gear Icon)** and enable **"Always-on VPN"** and **"Block connections without VPN"**.

---
*Note: To prevent evasion using secure DNS protocols, this app automatically blocks major public DNS-over-HTTPS (DoH) providers, forcing browsers like Chrome and Firefox to fall back to plaintext DNS which the local VPN can intercept.*