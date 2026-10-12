# Connection labels and darker unavailable text — RC5

Base: main `5acd874`. Version `1.0.0-rc.5`, code 38.

- Disabled action labels use a dedicated darker grey (`#606c76`). Other secondary
  text keeps its existing color; available action labels remain white.
- Simple mode's confirmed runtime connection names the paired computing unit.
  Local Termux uses the Android device name, with the model/this-phone fallback.
  Existing polling rejects stale target replies and clears connection text on errors.
- Connected vehicle status uses the Bluetooth service's actual connected adapter
  name rather than the saved selection. The accessor returns no name when stopped
  or failed; demo vehicles remain identified as demos.
- Connected USB receivers show manufacturer/product names without technical IDs or
  serial numbers in Simple mode. Home also checks physical attachment before showing
  a connected receiver name. Advanced diagnostic labels retain their detail.

70 JVM tests pass; debug APK builds and signature verifies. Lint retains the same
nine existing errors. Installer syntax checked. Native appearance/device metadata
have not been verified on a physical phone from this cloud environment.
