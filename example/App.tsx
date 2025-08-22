import React, { useCallback, useEffect, useState } from "react";
import ExpoZebraLinkOS, { type ZebraPrinterInfo } from "expo-zebra-link-os";
import {
  ActivityIndicator,
  Button,
  FlatList,
  SafeAreaView,
  Text,
  View,
  Alert,
  StyleSheet,
} from "react-native";
import { useEventListener } from "expo";

export default function App() {
  const [discoveredPrinters, setDiscoveredPrinters] = useState<
    ZebraPrinterInfo[]
  >([]);
  const [pairedPrinters, setPairedPrinters] = useState<ZebraPrinterInfo[]>([]);
  const [isDiscovering, setIsDiscovering] = useState(false);
  const [isPrinting, setIsPrinting] = useState(false);
  const [pairingAddress, setPairingAddress] = useState<string | null>(null);
  const [unpairingAddress, setUnpairingAddress] = useState<string | null>(null);
  const [checkingStatusAddress, setCheckingStatusAddress] = useState<
    string | null
  >(null);

  // Listen to printers being found during discovery
  useEventListener(
    ExpoZebraLinkOS,
    "onPrinterFound",
    (printer: ZebraPrinterInfo) => {
      if (!printer.paired) {
        // Ensure unpaired devices are shown in discovered and not duplicated in paired
        setDiscoveredPrinters((prev) => {
          const idx = prev.findIndex((p) => p.address === printer.address);
          if (idx >= 0) {
            const copy = prev.slice();
            copy[idx] = { ...printer, paired: false };
            return copy;
          }
          return [...prev, { ...printer, paired: false }];
        });
      }
    }
  );

  const refreshPairedPrinters = useCallback(async () => {
    try {
      const result = await ExpoZebraLinkOS.getPairedBluetoothDevices();
      if (!result.success) {
        Alert.alert(
          "Load paired failed",
          `${result.error.code}: ${result.error.message}`
        );
        return;
      }
      // Exclude those already shown in discovered list to avoid duplication
      const discoveredAddresses = new Set(
        discoveredPrinters.map((p) => p.address)
      );
      setPairedPrinters(
        result.data.filter((p) => !discoveredAddresses.has(p.address))
      );
    } catch (e) {
      Alert.alert("Load paired error", String(e));
    }
  }, [discoveredPrinters]);

  useEffect(() => {
    refreshPairedPrinters();
  }, []);

  const handleDiscover = useCallback(async () => {
    setDiscoveredPrinters([]);
    try {
      setIsDiscovering(true);
      const result = await ExpoZebraLinkOS.discoverBluetoothPrinters();
      if (!result.success) {
        Alert.alert(
          "Discovery failed",
          `${result.error.code}: ${result.error.message}`
        );
      }
    } catch (e) {
      Alert.alert("Discovery error", String(e));
    } finally {
      setIsDiscovering(false);
    }
  }, []);

  const handleCancelDiscovery = useCallback(async () => {
    try {
      const result = await ExpoZebraLinkOS.cancelDiscovery();
      if (!result.success) {
        Alert.alert(
          "Cancel failed",
          `${result.error.code}: ${result.error.message}`
        );
      }
    } catch (e) {
      Alert.alert("Cancel error", String(e));
    } finally {
      setIsDiscovering(false);
    }
  }, []);

  const handlePrintTest = useCallback(async (address: string) => {
    const zpl = "^XA^FO50,50^ADN,36,20^FDHello, World!^FS^XZ";
    try {
      setIsPrinting(true);
      const result = await ExpoZebraLinkOS.printZPLViaBluetooth(address, zpl);
      if (!result.success) {
        Alert.alert(
          "Print failed",
          `${result.error.code}: ${result.error.message}`
        );
      } else {
        Alert.alert("Success", "Test label printed successfully!");
      }
    } catch (e) {
      Alert.alert("Print error", String(e));
    } finally {
      setIsPrinting(false);
    }
  }, []);

  const handlePair = useCallback(
    async (address: string) => {
      try {
        setPairingAddress(address);
        const result = await ExpoZebraLinkOS.pairBluetoothPrinter(address);
        if (!result.success) {
          Alert.alert(
            "Pair failed",
            `${result.error.code}: ${result.error.message}`
          );
          return;
        }

        const printer = discoveredPrinters.find((p) => p.address === address);
        // Move device from discovered -> paired immediately
        setDiscoveredPrinters((prev) =>
          prev.filter((p) => p.address !== address)
        );

        // Add printer to paired list
        setPairedPrinters((prev) => [
          ...prev,
          {
            ...printer,
            paired: true,
          } as ZebraPrinterInfo,
        ]);

        Alert.alert("Success", "Device paired successfully");
      } catch (e) {
        Alert.alert("Pair error", String(e));
      } finally {
        setPairingAddress(null);
      }
    },
    [refreshPairedPrinters]
  );

  const handleUnpair = useCallback(
    async (address: string) => {
      try {
        setUnpairingAddress(address);
        const result = await ExpoZebraLinkOS.unPairBluetoothPrinter(address);
        if (!result.success) {
          Alert.alert(
            "Unpair failed",
            `${result.error.code}: ${result.error.message}`
          );
          return;
        }
        // Remove from paired list immediately and update discovered state if present
        setPairedPrinters((prev) => prev.filter((p) => p.address !== address));

        await refreshPairedPrinters();
        Alert.alert("Success", "Device unpaired successfully");
      } catch (e) {
        Alert.alert("Unpair error", String(e));
      } finally {
        setUnpairingAddress(null);
      }
    },
    [refreshPairedPrinters]
  );

  const handleCheckStatus = useCallback(async (address: string) => {
    try {
      setCheckingStatusAddress(address);
      const result = await ExpoZebraLinkOS.getBluetoothPrinterStatus(address);
      if (!result.success) {
        Alert.alert(
          "Status failed",
          `${result.error.code}: ${result.error.message}`
        );
        return;
      }
      const {
        isHeadCold,
        isHeadOpen,
        isHeadTooHot,
        isPaperOut,
        isPartialFormatInProgress,
        isPaused,
        isReadyToPrint,
        isReceiveBufferFull,
        isRibbonOut,
        labelLengthInDots,
        labelsRemainingInBatch,
        numberOfFormatsInReceiveBuffer,
        printMode,
      } = result.data;
      Alert.alert(
        "Printer status",
        `Ready: ${isReadyToPrint ? "Yes" : "No"}\n` +
          `Paused: ${isPaused ? "Yes" : "No"}\n` +
          `Paper out: ${isPaperOut ? "Yes" : "No"}\n` +
          `Head open: ${isHeadOpen ? "Yes" : "No"}\n` +
          `Head cold: ${isHeadCold ? "Yes" : "No"}\n` +
          `Head too hot: ${isHeadTooHot ? "Yes" : "No"}\n` +
          `Ribbon out: ${isRibbonOut ? "Yes" : "No"}\n` +
          `Receive buffer full: ${isReceiveBufferFull ? "Yes" : "No"}\n` +
          `Partial format in progress: ${isPartialFormatInProgress ? "Yes" : "No"}\n` +
          `Labels remaining in batch: ${labelsRemainingInBatch}\n` +
          `Number of formats in buffer: ${numberOfFormatsInReceiveBuffer}\n` +
          `Label length (dots): ${labelLengthInDots}\n` +
          `Print mode: ${printMode}`
      );
    } catch (e) {
      Alert.alert("Status error", String(e));
    } finally {
      setCheckingStatusAddress(null);
    }
  }, []);

  const showPrinterMenu = useCallback(
    (address: string, paired: boolean) => {
      const options: { text: string; onPress?: () => void; style?: any }[] = [];
      if (paired) {
        options.push({ text: "Unpair", onPress: () => handleUnpair(address) });
      } else {
        options.push({ text: "Pair", onPress: () => handlePair(address) });
      }
      options.push({
        text: "Test print",
        onPress: () => handlePrintTest(address),
      });
      options.push({
        text: "Check status",
        onPress: () => handleCheckStatus(address),
      });
      options.push({ text: "Cancel", style: "cancel" });
      Alert.alert("Printer actions", undefined, options);
    },
    [handlePair, handleUnpair, handlePrintTest, handleCheckStatus]
  );

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.headerContainer}>
        <Text style={styles.header}>Zebra Printer Tester</Text>
      </View>

      <View style={styles.actionsRow}>
        <Button
          title={isDiscovering ? "Cancel" : "Discover printers"}
          onPress={isDiscovering ? handleCancelDiscovery : handleDiscover}
          color={isDiscovering ? "#d32f2f" : undefined}
        />
        <Button title="Refresh paired" onPress={refreshPairedPrinters} />
      </View>

      {isDiscovering && (
        <View style={styles.loadingRow}>
          <ActivityIndicator size="small" />
          <Text style={styles.loadingText}>
            Searching for Bluetooth printers…
          </Text>
        </View>
      )}

      <View style={styles.listHeaderContainer}>
        <Text style={styles.listHeader}>Discovered printers</Text>
      </View>

      <FlatList
        data={discoveredPrinters}
        keyExtractor={(item) => item.address}
        renderItem={({ item }) => {
          const isPairing = pairingAddress === item.address;
          const isUnpairing = unpairingAddress === item.address;
          const isChecking = checkingStatusAddress === item.address;
          return (
            <View style={styles.listItem}>
              <Text style={styles.printerName}>
                {item.friendlyName ?? "Unknown"}
              </Text>
              <Text style={styles.printerId}>{item.address}</Text>
              <View style={styles.itemActionsRow}>
                <Text
                  style={[
                    styles.pairedBadge,
                    item.paired ? styles.paired : styles.unpaired,
                  ]}
                >
                  {item.paired ? "Paired" : "Not paired"}
                </Text>
                <Button
                  title={isPrinting || isChecking ? "Working…" : "More"}
                  onPress={() => showPrinterMenu(item.address, item.paired)}
                  disabled={
                    isPairing || isUnpairing || isPrinting || isChecking
                  }
                />
              </View>
            </View>
          );
        }}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>
              No printers yet. Tap "Discover printers" to scan.
            </Text>
          </View>
        }
        contentContainerStyle={
          discoveredPrinters.length === 0 ? styles.listContentEmpty : undefined
        }
      />

      <View style={styles.listHeaderContainer}>
        <Text style={styles.listHeader}>Paired printers</Text>
      </View>

      <FlatList
        data={pairedPrinters}
        keyExtractor={(item) => item.address}
        renderItem={({ item }) => {
          const isPairing = pairingAddress === item.address;
          const isUnpairing = unpairingAddress === item.address;
          const isChecking = checkingStatusAddress === item.address;
          return (
            <View style={styles.listItem}>
              <Text style={styles.printerName}>
                {item.friendlyName ?? "Unknown"}
              </Text>
              <Text style={styles.printerId}>{item.address}</Text>
              <View style={styles.itemActionsRow}>
                <Text style={[styles.pairedBadge, styles.paired]}>Paired</Text>
                <Button
                  title={isPrinting || isChecking ? "Working…" : "More"}
                  onPress={() => showPrinterMenu(item.address, true)}
                  disabled={
                    isPairing || isUnpairing || isPrinting || isChecking
                  }
                />
              </View>
            </View>
          );
        }}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>No paired printers.</Text>
          </View>
        }
        contentContainerStyle={
          pairedPrinters.length === 0 ? styles.listContentEmpty : undefined
        }
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
  },
  headerContainer: {
    paddingHorizontal: 20,
    paddingTop: 24,
    paddingBottom: 8,
  },
  header: {
    fontSize: 24,
    fontWeight: "600" as const,
  },
  permissionContainer: {
    backgroundColor: "#ffffff",
    marginHorizontal: 20,
    marginVertical: 8,
    borderRadius: 10,
    padding: 16,
    alignItems: "center" as const,
  },
  permissionText: {
    fontSize: 14,
    textAlign: "center" as const,
    marginBottom: 12,
    color: "#333",
  },
  permissionSpinner: {
    marginTop: 8,
  },
  actionsRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 20,
    paddingVertical: 12,
    gap: 12,
  },
  spacer: {
    width: 12,
  },
  loadingRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 20,
    paddingBottom: 8,
    width: "100%",
    gap: 10,
    justifyContent: "space-between",
  },
  loadingText: {
    fontSize: 14,
  },
  selectedContainer: {
    backgroundColor: "#ffffff",
    marginHorizontal: 20,
    marginVertical: 8,
    borderRadius: 10,
    padding: 12,
  },
  selectedLabel: {
    fontSize: 12,
    color: "#666",
    marginBottom: 4,
  },
  selectedValue: {
    fontSize: 14,
  },
  listHeaderContainer: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 4,
  },
  listHeader: {
    fontSize: 18,
    fontWeight: "500" as const,
  },
  listItem: {
    backgroundColor: "#ffffff",
    marginHorizontal: 20,
    marginVertical: 6,
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: "#e5e5e5",
  },
  listItemSelected: {
    borderColor: "#2e7d32",
    borderWidth: 2,
  },
  printerName: {
    fontSize: 16,
    marginBottom: 2,
  },
  printerId: {
    fontSize: 12,
    color: "#555",
  },
  itemActionsRow: {
    marginTop: 8,
    flexDirection: "row",
    alignItems: "center" as const,
    justifyContent: "space-between" as const,
    gap: 12,
  },
  pairedBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    overflow: "hidden" as const,
    color: "#fff",
    fontSize: 12,
  },
  paired: {
    backgroundColor: "#2e7d32",
  },
  unpaired: {
    backgroundColor: "#9e9e9e",
  },
  emptyContainer: {
    flex: 1,
    alignItems: "center" as const,
    justifyContent: "center" as const,
    paddingTop: 40,
  },
  emptyText: {
    color: "#666",
  },
  listContentEmpty: {
    flexGrow: 1,
  },
});
