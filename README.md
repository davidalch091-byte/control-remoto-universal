# Control Izzi para Android TV

Aplicación Android que convierte tu celular en un control remoto para decodificadores y televisores compatibles con **Android TV Remote**. Encuentra dispositivos en la red Wi-Fi, permite conectarse ingresando su IP y ofrece controles, teclado y accesos rápidos a aplicaciones.

> **Compatibilidad:** no es un control infrarrojo universal. El dispositivo debe ser compatible con el protocolo Android TV Remote y estar conectado a la misma red Wi-Fi que el celular. La disponibilidad de cada botón o aplicación puede variar según el equipo.

## Funciones

- Busca automáticamente dispositivos Android TV en la red local.
- Permite conectarse manualmente mediante la dirección IP.
- Empareja el celular con un código de seis caracteres que aparece en la pantalla del televisor.
- Incluye controles de navegación, encendido, volumen, canales, reproducción y guía.
- Ofrece un teclado para escribir en campos de búsqueda compatibles.
- Incluye accesos rápidos a YouTube, Netflix, Prime Video, Disney+, Max y Spotify; la aplicación correspondiente debe estar instalada en el televisor.
- Recuerda el último dispositivo emparejado para facilitar futuras conexiones.

## Requisitos

- Un dispositivo Android TV compatible con Android TV Remote.
- Celular y televisor conectados a la misma red Wi-Fi.
- Android 8.0 (API 26) o posterior en el celular.
- Para compilar: Android Studio, JDK 17 y Android SDK 34.

## Instalar y ejecutar desde Android Studio

1. Clona o descarga este repositorio y ábrelo en Android Studio.
2. Espera a que termine la sincronización de Gradle.
3. Conecta un celular Android o inicia un emulador compatible.
4. Ejecuta la configuración **app** con el botón **Run** de Android Studio.

La aplicación tiene como nombre visible **Control Izzi**.

## Cómo conectar el control

1. Conecta el celular y el televisor a la misma red Wi-Fi.
2. Abre **Control Izzi** y selecciona el televisor que aparezca en la lista. Si no aparece, escribe su dirección IP y pulsa **Conectar**.
3. La primera vez, la app solicitará el emparejamiento y el televisor mostrará un código de seis caracteres.
4. Ingresa el código en el celular. Cuando se complete la conexión, aparecerá el control remoto.
5. Usa las pestañas **Control**, **Números**, **Teclado** y **Apps** para acceder a las funciones.

Si el emparejamiento deja de funcionar, selecciona **Emparejar de nuevo**. Si la dirección IP del televisor cambia, vuelve a encontrarlo en la lista o ingresa su nueva IP.

## Solución de problemas

- **No aparece el televisor:** confirma que ambos dispositivos estén en la misma Wi-Fi y que la red permita la detección local. También puedes probar ingresando la IP manualmente.
- **No se conecta:** verifica que el televisor admita Android TV Remote y vuelve a emparejarlo.
- **Un acceso a una app no funciona:** comprueba que esa app esté instalada y disponible en el televisor.
- **El teclado no escribe:** primero selecciona un campo de texto en la pantalla del televisor. El envío de texto admite letras, números, espacios y algunos signos comunes.

## Detalles técnicos

- Proyecto Android nativo escrito en Kotlin.
- Protocolo Android TV Remote sobre la red local: puerto **6467** para emparejamiento y **6466** para control.
- Detección de servicios mediante DNS-SD (`_androidtvremote2._tcp`).
- `minSdk`: 26 · `targetSdk`: 34 · Java/Kotlin: 17.

## Licencia

No se especifica una licencia en este repositorio. Si quieres permitir que otras personas reutilicen o distribuyan el proyecto, agrega un archivo `LICENSE` con la licencia que elijas.
