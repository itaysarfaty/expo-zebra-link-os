## Expo Zebra Link OS

Android-only Expo module for Zebra printers over Bluetooth: discover, pair, print ZPL, and read status.

## Features

- Discover Bluetooth Zebra printers (with live `onPrinterFound` events)
- Pair / unpair printers
- List paired devices
- Print ZPL over Bluetooth
- Get printer status (ready, paper out, head open, paused)

## Install

```bash
npx expo install expo-zebra-link-os
```

## Quick start

```ts
import ExpoZebraLinkOS from "expo-zebra-link-os";

// Optional timeout in ms (default 60000)
const discovery = await ExpoZebraLinkOS.discoverBluetoothPrinters(30000);
if (discovery.success) console.log(discovery.data);

// Print
await ExpoZebraLinkOS.printZPLViaBluetooth(
  "00:11:22:33:44:55",
  "^XA^FO50,50^FDHi^FS^XZ"
);

// Pair / Unpair
await ExpoZebraLinkOS.pairBluetoothPrinter("00:11:22:33:44:55");
await ExpoZebraLinkOS.unPairBluetoothPrinter("00:11:22:33:44:55");

// Paired devices
const paired = await ExpoZebraLinkOS.getPairedBluetoothDevices();

// Status
const status =
  await ExpoZebraLinkOS.getBluetoothPrinterStatus("00:11:22:33:44:55");

// Cancel discovery (if needed)
await ExpoZebraLinkOS.cancelDiscovery();
```

### `onPrinterFound` event

```ts
import React from "react";
import ExpoZebraLinkOS, { ZebraPrinterInfo } from "expo-zebra-link-os";
import { useEventListener } from "expo";

export function DiscoverPrinters() {
  const [found, setFound] = React.useState<ZebraPrinterInfo[]>([]);

  // Automatically subscribes/unsubscribes to native events
  useEventListener(ExpoZebraLinkOS, "onPrinterFound", (printer) => {
    setFound((prev) => [printer, ...prev]);
  });

  const startDiscovery = async () => {
    // Optional timeout in ms (default 60000)
    await ExpoZebraLinkOS.discoverBluetoothPrinters(30000);
  };

  return null; // Render your UI using `found`
}
```

## API

- `discoverBluetoothPrinters(timeoutMs?: number): Promise<DiscoverPrintersResult>`
  - Optional `timeoutMs` for discovery duration in milliseconds. Default: `60000`.
- `cancelDiscovery(): Promise<{ success: true } | { success: false; error }>`
- `printZPLViaBluetooth(macAddress: string, zpl: string): Promise<PrintResult>`
- `pairBluetoothPrinter(macAddress: string): Promise<PairResult>`
- `unPairBluetoothPrinter(macAddress: string): Promise<PairResult>`
- `getPairedBluetoothDevices(): Promise<DiscoverPrintersResult>`
  - This will return all paired devices not just zebra printers
- `getBluetoothPrinterStatus(macAddress: string): Promise<GetPrinterStatusResult>`
- Event: `onPrinterFound(printer: ZebraPrinterInfo)`

### Types (brief)

- `ZebraPrinterInfo`: `{ address: string; friendlyName?: string; paired: boolean }`
- Common result: `{ success: true, data? } | { success: false, error: { code; message } }`
- `GetPrinterStatusResult.data`: `{ isHeadCold; isHeadOpen; isHeadTooHot; isPaperOut; isPartialFormatInProgress; isPaused; isReadyToPrint; isReceiveBufferFull; isRibbonOut; labelLengthInDots; labelsRemainingInBatch; numberOfFormatsInReceiveBuffer; printMode }`

## Connection pooling

- **Per-device reuse**: A single Bluetooth connection is maintained per MAC address and reused across calls.
- **Serialized access**: Operations to the same device are serialized to avoid concurrent writes.
- **On-demand open with retry**: Connections are opened when needed, with up to 2 retries (150 ms delay) and a liveness check.
- **Idle close**: If a connection is idle for 30 seconds, it is closed automatically in the background.
- **Failure handling**: On any operation error, the connection is closed and evicted so the next call starts fresh.
- **No manual management**: You do not need to connect/disconnect explicitly—just call print/status methods.

## Android

- Permissions are requested automatically when needed. Ensure Bluetooth is enabled.
- Declares: `BLUETOOTH/ADMIN` (< Android 12), `BLUETOOTH_SCAN/CONNECT` (Android 12+), `ACCESS_FINE_LOCATION`.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License.
