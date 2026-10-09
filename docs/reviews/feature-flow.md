# ORC Companion 0.9.2 feature flow

Home is ordered **Your Runtime → Manage → Features → Monitor**. The runtime
summary is read-only; there is one Runtime navigation entry under Manage.
Feature cards use tinted backgrounds, accent strips/borders, and distinct icons.

- Navigation owns navigation service start/stop, input source, phone motion/GPS,
  and sensor sharing (This phone / Local network).
- Automotive owns automotive service start/stop, input source, and Bluetooth/OBD.
- Radio owns local RTL-SDR USB connect/disconnect and the selected runtime's
  ADS-B service. It uses the existing application's USB owner and local proxy;
  it does not implement a new tuner or playback engine.
- Media owns camera and playback audio; Environment owns light and pressure.
- Runtime owns computing-unit selection, message-broker controls, and whole-core
  actions. Core actions still cover broker, navigation, and automotive together;
  their availability uses the complete service response, not only visible cards.
- Configuration owns paired computing units. Sensor sharing has moved out.

Single-service cards now expose both input selection and start/stop; the previous
profile-only mode hid those buttons. Service polling stops on navigation away
or backgrounding and rejects completions from an old selected runtime. Missing
service entries clear prior status and disable stale lifecycle buttons.

Starting Navigation with Android Bridge input ensures the phone sensor bridge
is enabled. A remote target enables LAN sensor sharing; the user can still turn
sharing off explicitly. Location permission must be resolved before the runtime
navigation start request is sent. When a permission prompt is needed, the screen
asks the user to grant it and tap Start again.

Receiver controls use the application's USB connection, so leaving Radio does
not interrupt reception. Disconnect stops the local proxy and releases USB;
a late permission completion after disconnect cannot restart the proxy. API
26–32 use legacy receiver registration, while API 33+ explicitly marks the USB
permission receiver as not exported.

Automated validation: 35 unit tests, debug APK build and signature verification.
Android lint retains the 10 errors previously reproduced on main. New phone
layout, service actions against a live runtime, USB permission denial/retry,
USB disconnect/reconnect, and real ADS-B reception need a phone smoke test.

The global master on/off control and text input remain separate outstanding work.
