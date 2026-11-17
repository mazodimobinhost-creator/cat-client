# اسکریپت وی‌پی‌اس Cat

هر فایل را با root روی همان سروری که بالای فایل نوشته اجرا کن. اول دو IP را داخل فایل عوض کن.

| فایل | کجا اجرا شود | کار |
|---|---|---|
| `iran-relay.sh` | سرور ایران | رله TCP+UDP به خارج |
| `iran-xray.sh` | سرور ایران | همان رله با Xray |
| `foreign-wireguard.sh` | سرور خارج | وایرگارد؛ conf برای Cat |
| `iran-dnat.sh` | سرور ایران | اگر وایرگارد را از IP ایران می‌خواهی |
| `foreign-openvpn.sh` | سرور خارج | OpenVPN TCP — Cat وصل نمی‌کند |

مثال وایرگارد:

```bash
# روی سرور خارج
nano foreign-wireguard.sh    # IRAN و FOREIGN را بگذار
bash foreign-wireguard.sh
# فایل /root/cat-wg-phone.conf را در Cat بچسبان
```

اگر گوشی باید به IP ایران وصل شود، `iran-dnat.sh` را هم روی ایران اجرا کن.
