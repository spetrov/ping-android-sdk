## [2.1.0]

#### Added
- Added OAuth 2.0 Device Authorization Grant (RFC 8628) support [SDKS-4784]
- Added Pushed Authorization Request (PAR) support for OIDC [SDKS-4231]
- Added standardized JSON configuration support [SDKS-5065] 
- Added `PollingCollector` for DaVinci flows [SDKS-4681]
- Added `QrCodeCollector` for DaVinci flows [SDKS-4679]
- Added `BooleanCollector` to support Checkbox and Switch input types in DaVinci forms [SDKS-4919]
- Added `ReadOnlyTextCollector` for DaVinci forms to support Terms of Service and agreement displays [SDKS-4927]
- Added support for links in Translatable Rich Text (Forms) [SDKS-4246]
- Added support for phone number extensions in `PhoneNumberCollector` [SDKS-4669]
- Support for Android 17 (the SDK has been verified to build and run correctly on Android 17) [SDKS-5191]
- Added AM/AIC transactional backchannel authentication support to the Journey module via `Journey.start(backchannelUri)` [SDKS-5157]

#### Fixed
- Fixed OATH and Push URI parsers to propagate typed `InvalidUriException` for structural URI parse errors [SDKS-5074]
- Fixed Ktor CIO engine dropping repeated `Set-Cookie` headers with mixed casing (KTOR-8614 workaround) [SDKS-4742]
- Fixed Push Number Challenge not surfacing a distinct failure for wrong-number responses [SDKS-5116]
- Fixed `PasswordCollector` not handling nested Password policies [SDKS-4694]

## [2.0.1]

#### Fixed
- Upgraded `bcpkix-jdk18on` from `1.81` to `1.84` to address a security vulnerability (CVE-2026-5588). [SDKS-5037]

## [2.0.0]
#### Added
- Added new `network` module [SDKS-4505]
- Added new `journey` module [SDKS-3917]
- Added new `mfa-commons` module [SDKS-4106]
- Added new `mfa-oath` module [SDKS-4021]
- Added new `mfa-push` module [SDKS-4023]
- Added new `auth-migration` module [SDKS-4716]
- Added new `fido` module [SDKS-4134]
- Added new `device-binding`, `device-binding-ui` and `device-binding-migration` modules [SDKS-4115]
- Added new `device-id` module [SDKS-4120]
- Added new `device-client` module [SDKS-4190]
- Added new `device-profile` module [SDKS-4300]
- Added new `device-root` module [SDKS-4365]
- Added new `recaptcha-enterprise` module [SDKS-4422]

## [1.3.0]
#### Added
- Added new `protect` module [SDKS-4069]
- Added new `oidc` login module with integrated browser support [SDKS-4150]
- Added support for Android 16 and updated `compileSdk` to version 36 and `minSdk` to 29 [SDKS-4278]
- Added Auth Tab support to the `BrowserLauncher` component [SDKS-3932]

#### Fixed
- Enhanced form handling in the DaVinci SDK to automatically reset form values after submission [SDKS-4511]
- Improved SDK storage configuration to simplify overrides [SDKS-4109]
- Enhanced Storage module with cache strategy support [SDKS-4112]
- Refactored logger initialization for session and cookie configurations [SDKS-4358]
- Updated `PhoneNumberCollector` to support new JSON format [SDKS-4198]
- Upgraded datastore library to version 1.1.7 [SDKS-4207]

## [1.2.0]
#### Added
- Support for native social login with Google and Facebook [SDKS-3449]
- Support for PingOne Forms MFA OTP components `DEVICE_REGISTRATION`, `DEVICE_AUTHENTICATION`, and `PHONE_NUMBER` [SDKS-3562]
- Support for accessing the previous `ContinueNode` node from `ErrorNode` [SDKS-3890]
- Support for accessing the `key` attribute of `LabelCollector` [SDKS-3957]
- Support for using StrongBox during key generation [SDKS-4098]

## [1.1.0]
#### Added
- Support for PingOne Forms field types `LABEL`, `CHECKBOX`, `DROPDOWN`, `COMBOBOX`, `RADIO`, `PASSWORD`, `PASSWORD_VERIFY`, `FLOWLINK` [SDKS-3649]
- Support for validation of PingOne Forms fields [SDKS-3649]
- Handling default values for PingOne Forms fields [SDKS-3649]
- Interface for access of ErrorNode with validation error [SDKS-3649]
- Support for Social Login with Browser Redirect [SDKS-3662]
- Support for `Accept-Language` header [SDKS-3622]
- New `browser` module [SDKS-3662]
- New `external-idp` module [SDKS-3662]
- Dynamic Environment Switching in Test Sample App [SDKS-3642]

#### Fixed
- A side effect on the Global Logger when configuring the DaVinci Logger [SDKS-3616]

## [1.0.0]
- General Availability release of the Ping SDK for Android

#### Added
- Initial release of the DaVinci module [SDKS-3186]