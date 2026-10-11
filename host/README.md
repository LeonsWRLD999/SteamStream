# SteamStream Host (PC)

1. Install Steam, Sunshine (https://github.com/LizardByte/Sunshine) and Tailscale on the PC.
2. Run: `python steamstream_host.py` (or double-click `build.bat` to build `dist\SteamStreamHost.exe`).
3. Copy the token and address it prints into the Android app.
4. To list your whole library (not just installed games): get a key at https://steamcommunity.com/dev/apikey,
   put it plus your 64-bit SteamID in `config.json` next to the script, and set your Steam game details to public.
5. Auto-start: press Win+R, type `shell:startup`, and put a shortcut to the exe there.
6. In the BIOS, enable Wake-on-LAN; in Windows Device Manager enable "Wake on Magic Packet" on your network adapter.
