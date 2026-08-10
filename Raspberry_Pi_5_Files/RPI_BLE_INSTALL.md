Raspberry Pi 5 BLE Broadcaster - Installation Guide
This repository contains a Raspberry Pi 5 BLE broadcaster that continuously advertises a short UTF-8 message in the BLE Manufacturer Specific Data field.
The current configuration advertises:
BLE local name: `RPi5 Beacon`
Manufacturer ID: `0xFFFF`
Default message: `bonus station`
Maximum message size: `23` bytes
Python environment: `/opt/ble-venv`
Python script: `/home/raspberrypi/Desktop/ble_broadcaster.py`
systemd service: `myAppBLE.service`
The broadcaster is intended to run automatically after boot, including when the Raspberry Pi is used headlessly.
---
1. How the setup works
The runtime flow is:
```text
Raspberry Pi boots
        |
        v
BlueZ Bluetooth service starts
        |
        v
systemd starts myAppBLE.service
        |
        v
/opt/ble-venv/bin/python runs ble_broadcaster.py
        |
        v
bluezero registers a BLE advertisement with BlueZ
        |
        v
Nearby BLE scanners receive:
    Local Name = "RPi5 Beacon"
    Manufacturer ID = 0xFFFF
    Manufacturer Data = UTF-8 message
```
No BLE connection is required on the receiving device. The message is included directly in the advertisement packet.
---
2. Repository files
`ble_broadcaster.py`
This is the main application.
It:
Finds the first available Bluetooth adapter.
Creates a non-connectable BLE broadcast advertisement.
Advertises the name `RPi5 Beacon`.
Encodes the message as UTF-8.
Limits the message to 23 bytes.
Places the message inside Manufacturer Specific Data under company ID `0xFFFF`.
Registers the advertisement with BlueZ.
Runs a GLib event loop so advertising remains active.
Unregisters the advertisement cleanly when the process stops.
The important advertisement line is:
```python
adv.manufacturer_data(MANUFACTURER_ID, msg_bytes)
```
In the installed Bluezero version, `manufacturer_data` is a method. Do not replace it with:
```python
adv.manufacturer_data = {...}
```
That would only create a Python attribute and would not update the BlueZ advertisement.
---
`myAppBLE.service`
This is the systemd unit that starts the broadcaster automatically.
The current uploaded service contains:
```ini
ExecStart=sudo /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
For a system service, `sudo` is unnecessary because system services run as root by default unless a `User=` directive is set.
The recommended service configuration is:
```ini
[Unit]
Description=BLE Broadcaster (RPi5 using bluezero)
After=bluetooth.target
Wants=bluetooth.target

[Service]
Type=simple
ExecStart=/opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
```
Important fields:
`After=bluetooth.target` - start after Bluetooth initialization.
`Wants=bluetooth.target` - request that the Bluetooth target be started.
`ExecStart=...` - run the BLE program using the virtual environment's Python.
`Restart=always` - restart the broadcaster if it exits.
`RestartSec=5` - wait five seconds before restarting.
`PYTHONUNBUFFERED=1` - make Python logs appear immediately in `journalctl`.
`WantedBy=multi-user.target` - enable startup during normal headless boot.
---
`startScriptBLE.sh`
The repository also contains:
```bash
#!/bin/bash

# Start my app
/home/raspberrypi/Desktop/ble_broadcaster
```
The current systemd service does not use this wrapper; it launches `ble_broadcaster.py` directly.
If you want to use the wrapper, update it to:
```bash
#!/bin/bash
exec /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
Then make it executable:
```bash
chmod +x /home/raspberrypi/Desktop/startScriptBLE.sh
```
You could then use this service line instead:
```ini
ExecStart=/home/raspberrypi/Desktop/startScriptBLE.sh
```
Using the shell wrapper is optional.
---
Installation
3. Install system dependencies
Update package information:
```bash
sudo apt update
```
Install Bluetooth, Python virtual-environment support, and native build dependencies:
```bash
sudo apt install -y \
    bluetooth \
    bluez \
    bluez-tools \
    python3-venv \
    python3-dev \
    libcairo2-dev \
    libgirepository1.0-dev \
    libdbus-1-dev \
    libglib2.0-dev \
    pkg-config
```
These packages provide:
BlueZ - Linux Bluetooth stack.
python3-venv - Python virtual environments.
Cairo / GLib / GObject / D-Bus development libraries - required by Python packages used by Bluezero.
pkg-config - allows native Python packages to locate installed system libraries.
---
4. Create the Python virtual environment
Raspberry Pi OS protects the system Python environment through PEP 668. Avoid installing project packages globally with:
```bash
sudo pip3 install ...
```
Create a dedicated virtual environment:
```bash
sudo python3 -m venv /opt/ble-venv
```
Give the Raspberry Pi user ownership:
```bash
sudo chown -R raspberrypi:raspberrypi /opt/ble-venv
```
Activate it:
```bash
source /opt/ble-venv/bin/activate
```
The terminal prompt should now look similar to:
```text
(ble-venv) raspberrypi@raspberrypi:~ $
```
---
5. Install Python dependencies
While the virtual environment is active, install the required packages without `sudo`:
```bash
pip install pycairo
pip install PyGObject
pip install dbus-python
pip install bluezero
```
Verify the main imports:
```bash
python -c "import bluezero, dbus, gi; print('BLE Python dependencies OK')"
```
Exit the environment:
```bash
deactivate
```
`deactivate` only changes the current terminal back to the system Python. The virtual environment remains installed.
---
6. Install the broadcaster script
Copy `ble_broadcaster.py` from the repository to the path expected by the current configuration:
```bash
cp ble_broadcaster.py /home/raspberrypi/Desktop/ble_broadcaster.py
```
Make it executable:
```bash
chmod +x /home/raspberrypi/Desktop/ble_broadcaster.py
```
The script already uses this shebang:
```python
#!/opt/ble-venv/bin/python
```
This makes direct execution use the correct virtual-environment Python.
---
7. BlueZ experimental advertising support
The Bluezero advertising implementation used by this project documents LE advertising as requiring BlueZ experimental support.
First test the broadcaster. If it works, do not change the Bluetooth daemon configuration.
If your BlueZ build requires experimental advertising support, inspect the current service:
```bash
systemctl cat bluetooth.service
```
Then create an override:
```bash
sudo systemctl edit bluetooth.service
```
Use the correct `bluetoothd` path shown by your system. For example:
```ini
[Service]
ExecStart=
ExecStart=/usr/libexec/bluetooth/bluetoothd --experimental
```
Some Raspberry Pi OS versions instead use:
```text
/usr/lib/bluetooth/bluetoothd
```
Reload and restart:
```bash
sudo systemctl daemon-reload
sudo systemctl restart bluetooth
```
Verify the adapter:
```bash
bluetoothctl show
```
You should see a controller with:
```text
Powered: yes
```
---
8. Test the broadcaster manually
Before installing the systemd service, test the Python program directly:
```bash
sudo /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
Expected output is similar to:
```text
Starting BLE broadcaster with message: 'bonus station'
Using adapter: XX:XX:XX:XX:XX:XX
Manufacturer data length: 13 bytes, data=b'bonus station'
Advertising… (Ctrl+C to stop if running manually).
Advertisement registered
```
Press `Ctrl+C` to stop.
You can also pass another short message:
```bash
sudo /opt/ble-venv/bin/python \
    /home/raspberrypi/Desktop/ble_broadcaster.py \
    "station 01"
```
The script encodes the message as UTF-8 and limits it to 23 bytes:
```python
msg_bytes = message.encode("utf-8")[:MAX_MANUFACTURER_BYTES]
```
---
9. Verify from Android
Use a BLE scanner such as nRF Connect:
Enable Bluetooth.
Start scanning.
Find:
```text
RPi5 Beacon
```
Expand the advertisement.
Look for Manufacturer Specific Data.
The advertisement uses:
```text
Manufacturer ID: 0xFFFF
```
A scanner may show the manufacturer name as:
```text
N/A
```
That is expected for this project/test ID.
The manufacturer bytes contain the UTF-8 message.
A BLE connection is not required.
---
Automatic startup with systemd
10. Install the service
Copy the unit:
```bash
sudo cp myAppBLE.service /etc/systemd/system/myAppBLE.service
```
Edit it:
```bash
sudo nano /etc/systemd/system/myAppBLE.service
```
Use the recommended `ExecStart`:
```ini
ExecStart=/opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
Do not include `sudo` inside `ExecStart`.
Reload systemd:
```bash
sudo systemctl daemon-reload
```
Enable startup at boot:
```bash
sudo systemctl enable myAppBLE.service
```
Start immediately:
```bash
sudo systemctl start myAppBLE.service
```
Check status:
```bash
sudo systemctl status myAppBLE.service
```
Press `q` to exit the status view.
---
11. Useful service commands
Start:
```bash
sudo systemctl start myAppBLE.service
```
Stop:
```bash
sudo systemctl stop myAppBLE.service
```
Restart:
```bash
sudo systemctl restart myAppBLE.service
```
Enable at boot:
```bash
sudo systemctl enable myAppBLE.service
```
Disable at boot:
```bash
sudo systemctl disable myAppBLE.service
```
Check whether enabled:
```bash
systemctl is-enabled myAppBLE.service
```
Check whether running:
```bash
systemctl is-active myAppBLE.service
```
---
12. View logs
Recent logs:
```bash
sudo journalctl -u myAppBLE.service
```
Live logs:
```bash
sudo journalctl -u myAppBLE.service -f
```
Logs for the current boot:
```bash
sudo journalctl -u myAppBLE.service -b
```
---
13. Avoid running two broadcasters simultaneously
Do not run the manual broadcaster while `myAppBLE.service` is active.
Check:
```bash
ps aux | grep '[b]le_broadcaster.py'
```
For manual testing:
```bash
sudo systemctl stop myAppBLE.service
```
Run the script:
```bash
sudo /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
When finished:
```bash
sudo systemctl start myAppBLE.service
```
---
14. Test headless startup
Reboot:
```bash
sudo reboot
```
After the Raspberry Pi boots:
Do not start the Python program manually.
Scan from Android.
Confirm `RPi5 Beacon` appears.
Confirm Manufacturer Specific Data is present.
Over SSH, verify:
```bash
systemctl status myAppBLE.service
```
and:
```bash
sudo journalctl -u myAppBLE.service -b
```
---
Configuration
The main configuration is at the top of `ble_broadcaster.py`:
```python
DEFAULT_MESSAGE = "bonus station"
LOCAL_NAME = "RPi5 Beacon"
MANUFACTURER_ID = 0xFFFF
MAX_MANUFACTURER_BYTES = 23
```
Change the default message:
```python
DEFAULT_MESSAGE = "station 01"
```
Change the BLE name:
```python
LOCAL_NAME = "Campus Beacon"
```
The sender and receiver must agree on the manufacturer ID:
```python
MANUFACTURER_ID = 0xFFFF
```
For example, the Android scanner should use the same ID:
```kotlin
val companyId = 0xFFFF
```
After editing the installed Python file:
```bash
sudo systemctl restart myAppBLE.service
```
---
Troubleshooting
`externally-managed-environment`
Do not use:
```bash
sudo pip3 install bluezero
```
Use:
```bash
source /opt/ble-venv/bin/activate
pip install bluezero
deactivate
```
---
`ModuleNotFoundError: No module named 'bluezero'`
Use the virtual-environment interpreter:
```bash
/opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
For a manual root test:
```bash
sudo /opt/ble-venv/bin/python /home/raspberrypi/Desktop/ble_broadcaster.py
```
---
`ModuleNotFoundError: No module named 'dbus'`
Install the system libraries:
```bash
sudo apt install -y libdbus-1-dev libglib2.0-dev
```
Then:
```bash
source /opt/ble-venv/bin/activate
pip install dbus-python
deactivate
```
---
Cairo build failure
If pip reports:
```text
Dependency "cairo" not found
```
install:
```bash
sudo apt install -y libcairo2-dev pkg-config python3-dev
```
Then retry the Python package installation.
---
Device is visible but Manufacturer Data is missing
The correct Bluezero API for the installed version is:
```python
adv.manufacturer_data(MANUFACTURER_ID, msg_bytes)
```
Do not use:
```python
adv.manufacturer_data = {
    MANUFACTURER_ID: list(msg_bytes)
}
```
Also keep the message short enough to fit the advertisement.
---
nRF Connect shows `N/A`
With:
```python
MANUFACTURER_ID = 0xFFFF
```
the scanner may show the company/manufacturer name as `N/A`.
This does not mean the payload is missing.
---
Advertisement appears unstable or flips
Check that only one broadcaster is running:
```bash
ps aux | grep '[b]le_broadcaster.py'
```
Stop the service before manual testing:
```bash
sudo systemctl stop myAppBLE.service
```
Running a manual instance and the systemd instance at the same time can produce confusing scanner output.
---
Uninstall
Stop and disable:
```bash
sudo systemctl stop myAppBLE.service
sudo systemctl disable myAppBLE.service
```
Remove the unit:
```bash
sudo rm /etc/systemd/system/myAppBLE.service
sudo systemctl daemon-reload
```
Remove the virtual environment if no longer needed:
```bash
sudo rm -rf /opt/ble-venv
```
Remove the installed Python file if desired:
```bash
rm /home/raspberrypi/Desktop/ble_broadcaster.py
```
---
Current BLE payload format
```text
BLE Advertisement
├── Local Name: RPi5 Beacon
├── TX Power: included
└── Manufacturer Specific Data
    ├── Manufacturer ID: 0xFFFF
    └── Data: UTF-8 encoded message, maximum 23 bytes
```
Example:
```text
bonus station
```
is encoded as UTF-8 and transmitted in Manufacturer Specific Data.
