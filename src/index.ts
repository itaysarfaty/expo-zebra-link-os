// Reexport the native module. On web, it will be resolved to ExpoZebraLinkOSModule.web.ts
// and on native platforms to ExpoZebraLinkOSModule.ts
export { default } from "./ExpoZebraLinkOSModule";
export * from "./ExpoZebraLinkOS.types";
