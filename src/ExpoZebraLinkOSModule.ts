import { NativeModule, requireNativeModule } from "expo";

import type {
  DiscoverPrintersResult,
  ExpoZebraLinkOSModuleEvents,
  CancelDiscoveryResult,
  PrintResult,
  PairResult,
  GetPrinterStatusResult,
} from "./ExpoZebraLinkOS.types";

declare class ExpoZebraLinkOSModule extends NativeModule<ExpoZebraLinkOSModuleEvents> {
  discoverBluetoothPrinters(
    timeoutMs?: number
  ): Promise<DiscoverPrintersResult>;
  cancelDiscovery(): Promise<CancelDiscoveryResult>;
  printZPLViaBluetooth(macAddress: string, zpl: string): Promise<PrintResult>;
  pairBluetoothPrinter(macAddress: string): Promise<PairResult>;
  unPairBluetoothPrinter(macAddress: string): Promise<PairResult>;
  getPairedBluetoothDevices(): Promise<DiscoverPrintersResult>;
  getBluetoothPrinterStatus(
    macAddress: string
  ): Promise<GetPrinterStatusResult>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoZebraLinkOSModule>("ExpoZebraLinkOS");
