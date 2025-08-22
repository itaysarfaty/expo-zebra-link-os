export type ExpoZebraLinkOSModuleEvents = {
  onPrinterFound: (printer: ZebraPrinterInfo) => void;
};

export type ZebraPrinterInfo = {
  address: string;
  friendlyName?: string;
  paired: boolean;
};

export type ErrorObject = {
  code: string;
  message: string;
};

export type DiscoverPrintersResult =
  | {
      success: true;
      data: ZebraPrinterInfo[];
    }
  | {
      success: false;
      error: ErrorObject;
    };

export type CancelDiscoveryResult =
  | {
      success: true;
    }
  | {
      success: false;
      error: ErrorObject;
    };

export type PrintResult =
  | {
      success: true;
    }
  | {
      success: false;
      error: ErrorObject;
    };

export type PairResult =
  | {
      success: true;
    }
  | {
      success: false;
      error: ErrorObject;
    };

export type ZplPrintMode =
  | "APPLICATOR" // Applicator print mode
  | "CUTTER" // Cutter print mode
  | "DELAYED_CUT" // Delayed cut print mode
  | "KIOSK" // Kiosk print mode
  | "LINERLESS_PEEL" // Linerless peel print mode
  | "LINERLESS_REWIND" // Linerless rewind print mode
  | "PARTIAL_CUTTER" // Partial cutter print mode
  | "PEEL_OFF" // Peel-off print mode
  | "REWIND" // Rewind print mode
  | "RFID" // RFID print mode
  | "TEAR_OFF" // Tear-off print mode (this also implies Linerless Tear print mode)
  | "UNKNOWN"; // Unknown print mode

export type PrinterStatusData = {
  isHeadCold: boolean;
  isHeadOpen: boolean;
  isHeadTooHot: boolean;
  isPaperOut: boolean;
  isPartialFormatInProgress: boolean;
  isPaused: boolean;
  isReadyToPrint: boolean;
  isReceiveBufferFull: boolean;
  isRibbonOut: boolean;
  labelLengthInDots: number;
  labelsRemainingInBatch: number;
  numberOfFormatsInReceiveBuffer: number;
  printMode: ZplPrintMode;
};

export type GetPrinterStatusResult =
  | {
      success: true;
      data: PrinterStatusData;
    }
  | {
      success: false;
      error: ErrorObject;
    };
