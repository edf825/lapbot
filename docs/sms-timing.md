# Reverse Engineering SMS-Timing Resource Keys & WebSocket URLs

This guide details the procedure for discovering the SMS-Timing `RESOURCE_KEY` and WebSocket URL for any TeamSport karting centre (such as TeamSport Leicester) using the Android app (`com.teamsport.scotkartcambuslang`).

---

## Background & Architecture

The TeamSport Android app is built using **Capacitor / Ionic** (Angular running inside a WebView).
Instead of hardcoding every track's live timing WebSocket server and resource key, the app queries the SMS-Timing backend APIs at runtime:

1. **Client Key Lookup:** Each venue has a unique `clientKey` (e.g. `teamsportleicester`, `teamsportfarnborough`).
2. **Backend Authentication / Connection Info:**
   - The app makes a call to `https://backend.sms-timing.com/api/connectioninfo/encrypted`.
   - The parameter `message` is the AES-encrypted `clientKey` (using a static AES key embedded in the app).
   - The response provides a venue-specific API host (`ServiceAddress`, e.g. `mobile-api4.sms-timing.com`) and an access token (`AccessToken`, which acts as header `X-Fast-AccessToken`).
3. **Device Token Generation:**
   - The app calls `GET https://<ServiceAddress>/api/device/token/<clientKey>?os=2&kind=1&locale=en` with header `X-Fast-AccessToken`.
   - Returns a unique device token string (used as header `X-Fast-DeviceToken`).
4. **Live Timing Settings & Resource Discovery:**
   - The app calls `GET https://<ServiceAddress>/api/livetiming/settings/<clientKey>`.
   - Returns:
     - `resources`: Array of resources containing `id`, `name`, and `key` (e.g. `19476@teamsportleicester`).
     - `settings`: The WebSocket endpoint (`host`, `wsPort`, `wssPort`, e.g. `webserver4.sms-timing.com:10015`).

---

## Prerequisites

- Android phone with USB debugging enabled.
- `adb` installed and connected to the phone (`adb devices`).
- Node.js installed locally (for running the cryptographic handshake script).

---

## Step 1: Pull APK & Find the Venue `clientKey`

Extract the base APK from the device:
```bash
APK_PATH=$(adb shell pm path com.teamsport.scotkartcambuslang | grep base.apk | cut -d: -f2)
adb pull "$APK_PATH" /tmp/teamsport.apk
```

Search the packaged client list inside the app assets (`main.*.js`):
```bash
python3 -c "
import zipfile, json, re

apk = zipfile.ZipFile('/tmp/teamsport.apk')
for name in apk.namelist():
    if 'main.' in name and name.endswith('.js'):
        data = apk.read(name).decode('utf-8', errors='ignore')
        m = re.search(r'clients:\'(\[.*?\])\'', data)
        if m:
            clients = json.loads(m.group(1))
            for c in clients:
                print(f\"{c['name']}: clientKey={c['clientKey']}, baseAddress={c.get('baseAddress')}\")
"
```

Find the target venue (e.g. Leicester):
```text
Leicester Teamsport & Putt Club: clientKey=teamsportleicester, baseAddress=4
```

---

## Step 2: Query the SMS-Timing Backend for Credentials & Timing Settings

The app uses CryptoJS AES encryption with a hardcoded key (`zEiVcDLzBdfi>=.rRA.kPYk%`) to authenticate against `backend.sms-timing.com`.

Run this Node.js script to query the backend and retrieve the timing settings:

```bash
mkdir -p /tmp/sms_timing && cd /tmp/sms_timing
npm install crypto-js
```

Create `/tmp/sms_timing/get_timing_key.js`:

```javascript
const CryptoJS = require('crypto-js');
const https = require('https');

const CLIENT_KEY = process.argv[2] || 'teamsportleicester';
const AES_KEY = 'zEiVcDLzBdfi>=.rRA.kPYk%';

function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers }, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, data: JSON.parse(data) });
        } catch {
          resolve({ status: res.statusCode, data: data.trim() });
        }
      });
    }).on('error', reject);
  });
}

async function main() {
  console.log(`=== Resolving SMS-Timing Resource Key for: ${CLIENT_KEY} ===\n`);

  // 1. Encrypt client key
  const iv = CryptoJS.lib.WordArray.random(16);
  const encrypted = CryptoJS.AES.encrypt(
    CryptoJS.enc.Utf8.parse(CLIENT_KEY),
    AES_KEY,
    { iv }
  ).toString();

  // 2. Obtain connection info
  const connUrl = `https://backend.sms-timing.com/api/connectioninfo/encrypted?message=${encodeURIComponent(encrypted)}&locationType=3&type=mobile`;
  const connRes = await get(connUrl);
  if (connRes.status !== 200) {
    throw new Error(`Failed to get connection info: ${connRes.status} ${connRes.data}`);
  }

  const { ServiceAddress, AccessToken } = connRes.data;
  console.log(`Service Address: ${ServiceAddress}`);
  console.log(`Access Token:    ${AccessToken}\n`);

  // 3. Obtain device token
  const tokenUrl = `https://${ServiceAddress}/api/device/token/${CLIENT_KEY}?os=2&kind=1&locale=en`;
  const tokenRes = await get(tokenUrl, {
    'X-Fast-AccessToken': AccessToken,
  });
  const deviceToken = typeof tokenRes.data === 'string' ? tokenRes.data.replace(/"/g, '') : tokenRes.data;
  console.log(`Device Token:    ${deviceToken}\n`);

  // 4. Query livetiming settings
  const settingsUrl = `https://${ServiceAddress}/api/livetiming/settings/${CLIENT_KEY}`;
  const settingsRes = await get(settingsUrl, {
    'X-Fast-AccessToken': AccessToken,
    'X-Fast-DeviceToken': deviceToken,
    'X-Fast-Version': '2.7.6',
  });

  if (settingsRes.status !== 200) {
    throw new Error(`Failed to get settings: ${settingsRes.status} ${JSON.stringify(settingsRes.data)}`);
  }

  const { resources, settings } = settingsRes.data;
  console.log('=== Live Timing Settings ===');
  console.log(`WebSocket Host:  ${settings.host}`);
  console.log(`WebSocket Port:  ${settings.wssPort}`);
  console.log(`WebSocket URL:   wss://${settings.host}:${settings.wssPort}/`);
  console.log('\n=== Resources ===');
  for (const r of resources) {
    console.log(`- Resource Name: ${r.name} (${r.resourceName})`);
    console.log(`  Resource ID:   ${r.id}`);
    console.log(`  Resource Key:  ${r.key}`);
  }
}

main().catch(console.error);
```

Run the script:
```bash
node /tmp/sms_timing/get_timing_key.js teamsportleicester
```

Output:
```text
=== Resolving SMS-Timing Resource Key for: teamsportleicester ===

Service Address: mobile-api4.sms-timing.com
Access Token:    79cwrbxxiwrexsebdwx

Device Token:    1111111130R38250C63

=== Live Timing Settings ===
WebSocket Host:  webserver4.sms-timing.com
WebSocket Port:  10015
WebSocket URL:   wss://webserver4.sms-timing.com:10015/

=== Resources ===
- Resource Name: T1 - Electric (T1 - Electric)
  Resource ID:   19476
  Resource Key:  19476@teamsportleicester
```

---

## Step 3: Verify the WebSocket Feed

Connect directly to the WebSocket and issue the `START <resourceKey>` command:

```python
import asyncio, websockets, ssl, json

async def verify():
    ssl_ctx = ssl.create_default_context()
    url = 'wss://webserver4.sms-timing.com:10015/'
    async with websockets.connect(url, ssl=ssl_ctx, ping_interval=None) as ws:
        await ws.send('START 19476@teamsportleicester')
        msg = await asyncio.wait_for(ws.recv(), timeout=5)
        data = json.loads(msg)
        print("Success! Live session keys:", list(data.keys()))
        print("Drivers currently on track:", len(data.get('D', [])))

asyncio.run(verify())
```

---

## Reference Track Configurations

| Track | Client Key | Resource ID | Resource Key | WebSocket URL |
|---|---|---|---|---|
| **TeamSport Farnborough** | `teamsportfarnborough` | `260831` | `260831@teamsportfarnborough` | `wss://webserver3.sms-timing.com:10015/` |
| **TeamSport Leicester** | `teamsportleicester` | `19476` | `19476@teamsportleicester` | `wss://webserver4.sms-timing.com:10015/` |
