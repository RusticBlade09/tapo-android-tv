#!/system/bin/sh
# TEST ONLY: adds 4 fake camera IPs (.241-.244) to the app prefs to test Dashboards with >4 cameras.
# Run: adb push tools/fake_cams.sh /data/local/tmp/ ; adb shell chmod 755 /data/local/tmp/fake_cams.sh ;
#      adb shell am force-stop za.munnik.tapoatv ; adb shell run-as za.munnik.tapoatv sh /data/local/tmp/fake_cams.sh
# RESTORE after: adb shell am force-stop za.munnik.tapoatv ; adb shell run-as za.munnik.tapoatv sh -c "cd /data/data/za.munnik.tapoatv && cp files/tapo.bak shared_prefs/tapo.xml && rm files/tapo.bak"
# (Git Bash: export MSYS_NO_PATHCONV=1 first)
cd /data/data/za.munnik.tapoatv || exit 1
mkdir -p files
[ -f files/tapo.bak ] || cp shared_prefs/tapo.xml files/tapo.bak
sed -i 's#<set name="cams">#<set name="cams"><string>192.168.0.241</string><string>192.168.0.242</string><string>192.168.0.243</string><string>192.168.0.244</string>#' shared_prefs/tapo.xml
grep -c 'name="cams"' shared_prefs/tapo.xml; grep -o '192.168.0.24[0-9]' shared_prefs/tapo.xml | wc -l; ls -l files
