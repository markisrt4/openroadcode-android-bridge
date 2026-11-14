# SDRForge Radio card integration

The Radio card retains the existing Android USB proxy controls. A separate
libSDRForge section provides asynchronous loopback REST health, start, stop,
and FM tune controls. These requests target 127.0.0.1:5570 and do not own USB.

The source selector stores the preferred source in Android preferences only.
It deliberately does **not** claim to start a Termux process or switch an
already-running receiver. Launching SDRForge from Android requires a separately
implemented and authenticated Termux service-management contract.

For a hardware-free test in Termux:

```sh
cd ~/src/libSDRForge
python examples/live_transport_server.py --simulate --frequency 94.7 --warmup 0
```

Then open Companion > Radio and use Check, Start, Tune, Stop.

Current limitations:
- No Android-side ZMQ subscriber or measured stream rates yet.
- No live DSP counters, RDS metadata fields, or USB recovery diagnostics.
- REST status does not expose the receiver worker's active/faulted state.
- Android USB ownership and Termux RTL-SDR ownership remain separate; never
  open the same device from both paths simultaneously.
- The Android card cannot launch the SDRForge server automatically yet.

Future milestones: service-manager integration, structured REST status,
ZMQ rate monitoring, and bounded diagnostic history.
