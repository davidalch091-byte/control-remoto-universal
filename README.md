# Control Izzi TV (Android)

App de control remoto para decodificadores izzi con Android TV. Busca la TV en tu WiFi, te pide el código que sale en pantalla y te da un control con flechas, OK, volumen, canales y números.

## Cómo instalarla en tu celular

1. Abre esta carpeta (`IzziRemote`) con **Android Studio** y deja que sincronice Gradle (si pide versión, acepta Gradle 8.7 o más nueva).
2. En el celular activa **Opciones de desarrollador → Depuración USB** y conéctalo por cable.
3. Dale al botón ▶ (Run). Se instala la app "Control Izzi".

## Cómo se usa

1. Celular y decodificador en el **mismo WiFi**.
2. Abre la app: aparece tu TV en la lista (si no, escribe su IP y toca Conectar).
3. Tócala. La TV muestra un código de 6 caracteres: escríbelo en el celular.
4. Listo, aparece el control. La próxima vez conecta directo.

Si deja de conectar, toca **Emparejar de nuevo**.

## Notas

- Usa el protocolo de Android TV Remote por WiFi (puertos 6466 y 6467), el mismo que la app Google TV.
- Si la IP de la TV cambia (DHCP), vuelve a tocarla en la lista y empareja otra vez.
