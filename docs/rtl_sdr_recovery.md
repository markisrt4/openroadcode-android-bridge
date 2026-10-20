# RTL-SDR stream recovery

Android Bridge remains a USB transport. RTL2832U and tuner initialization,
sample-rate/frequency restoration, and recovery of the SDR++ callback loop belong
to the Linux ORCU-patched librtlsdr.

On three consecutive synchronous bulk-IN failures the Bridge retires the USB
connection and reopens it. The zero-length IQ terminator is sent **after** the
reopen attempt completes, so the Linux client's recovery does not intentionally
race a still-pending reopen. If sending the terminator fails, the USB reopen has
already been attempted.

Shared interface claims belong to the exact Android connection on which they
were acquired. Old clients may finish cleanup after a replacement stream has
claimed interface 0; their cleanup is ignored for the replacement connection.
Repeated claims by one client are idempotent. Each stream socket ends after its
stream session, and its STREAM_STOP reader is unblocked during cleanup.

The existing APK workflow runs RtlSdrInterfaceClaimsTest, covering stale client
cleanup after reopen, shared claims, repeated release, and interface isolation.
These are accounting tests; they do not establish Android USB stability.

## Device validation

Install the APK for the exact media-rtl-sdr-bridge HEAD using
development/termux/install_latest_apk.sh. Keep the dongle connected throughout
the test. Use ORC android-rtl-tcp-source with the recovery initialization fix
7ca2825c50f2c3646c552dc197607de6ef83fbff or later.

Start normal SDR++ reception. If USB bulk streaming fails, verify that Linux
reinitializes successfully and resumes IQ progress **and** the waterfall
without stopping or restarting SDR++. A stream-start log only confirms that
the start command was sent. It does not confirm any IQ has been received.

Capture the Bridge receiver diagnostics and the latest Linux recovery/IQ log
segment before restarting. Old sessions may be present in /tmp/sdrpp.log;
normalize literal backslash-n log separators and take the last session when
reviewing. Long-duration stability and intelligible FM audio still require
device validation.
