# Smouha Call protocol (v1.2)

LAN bridge between **Smouha Papper** (Chrome extension) and **Smouha Call** (Android).

## Defaults

- Port: **8765**
- Same Wi-Fi as the PC
- Brand tag (fixed): `ds60ashrafamin` — display only, not the rotating pair secret
- Default phone name: `ds60-phone1` (editable per device)

## Endpoints

| Method | Path | Auth | Notes |
|--------|------|------|--------|
| GET | `/v1/ping` | Bearer | Health + deviceName + brand |
| POST | `/v1/pair` | — | Consumes short-lived secret; returns session token |
| POST | `/v1/call` | Bearer | Starts call; `{ ok, started, detail }` |
| POST | `/v1/whatsapp` | Bearer | Opens WhatsApp; same shape |
| GET | `/v1/info` | — | Public device snapshot |
| GET | `/v1/activity` | Bearer | Last call/WA log entries |

## Pairing

1. Phone: Start bridge → copy **pair text** (JSON includes rotating `secret` + fixed `brand`).
2. Extension: Settings → Phones → paste → Pair.
3. After success, phone hides pair text by default; brand tag stays visible.
4. Each pair needs a **fresh** secret (New pair code).

## Call reliability

- Phone places the call then ACKs HTTP.
- If the PC loses the ACK (timeout/Wi-Fi), extension treats it as **uncertain success** ("Sent") so UI does not show hard Failed while the phone rings.
