#!/opt/ble-venv/bin/python
"""
Simple BLE advertiser for Raspberry Pi 5 using python-bluezero.

Usage (manual test):
    sudo /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py "Your message here"
"""

import sys
import signal

import dbus.mainloop.glib
from gi.repository import GLib
from bluezero import adapter, advertisement

# Default message if none is provided on the command line
DEFAULT_MESSAGE = "bonus station"
LOCAL_NAME = "RPi5 Beacon"

# Test manufacturer ID (0xFFFF is reserved for testing/private use)
MANUFACTURER_ID = 0xFFFF

# Keep manufacturer data small – BLE adv payload is tiny
MAX_MANUFACTURER_BYTES = 23


# Set DBus to use the GLib main loop once at import time
dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)


def create_advertisement(message: str):
    """Create and register the BLE advertisement."""
    # Find the first available Bluetooth adapter
    adapters = list(adapter.Adapter.available())
    if not adapters:
        raise RuntimeError("No Bluetooth adapters found")

    dongle = adapters[0]
    print(f"Using adapter: {dongle.address}")

    # Create advertisement object
    adv = advertisement.Advertisement(0, "broadcast")
    adv.local_name = LOCAL_NAME
    adv.service_UUIDs = []  # not advertising any specific service UUIDs
    adv.include_tx_power = True

    # Encode message and trim to allowed size
    msg_bytes = message.encode("utf-8")[:MAX_MANUFACTURER_BYTES]
    print(f"Manufacturer data length: {len(msg_bytes)} bytes, data={msg_bytes!r}")

    # IMPORTANT: call the method, do NOT assign adv.manufacturer_data = ...
    adv.manufacturer_data(MANUFACTURER_ID, msg_bytes)

    # Register the advertisement with BlueZ
    ad_manager = advertisement.AdvertisingManager(dongle.address)
    ad_manager.register_advertisement(adv, {})

    return adv, ad_manager


def main():
    # Use CLI argument as message if provided
    if len(sys.argv) > 1:
        message = " ".join(sys.argv[1:])
    else:
        message = DEFAULT_MESSAGE

    print(f"Starting BLE broadcaster with message: '{message}'")

    adv = None
    ad_manager = None

    # Create GLib main loop (this keeps DBus + advertising alive)
    loop = GLib.MainLoop()

    # Allow Ctrl+C to stop the loop when run manually
    def handle_sigint(sig, frame):
        print("\nSIGINT received, stopping advertising…")
        loop.quit()

    signal.signal(signal.SIGINT, handle_sigint)

    try:
        adv, ad_manager = create_advertisement(message)
        print("Advertising… (Ctrl+C to stop if running manually).")
        loop.run()
    except Exception as e:
        print(f"Error while advertising: {e}")
    finally:
        if ad_manager and adv:
            try:
                ad_manager.unregister_advertisement(adv)
                print("Advertisement unregistered.")
            except Exception as e:
                print(f"Error while unregistering advertisement: {e}")


if __name__ == "__main__":
    main()
