# Cuentas Microsoft para Minecraft Java

PRISSMA usa el flujo de código de dispositivo para solicitar una sesión personal de Microsoft, intercambiarla por una sesión de Xbox y consultar el perfil de Minecraft Java. La autorización se completa en el navegador de Microsoft. Una cuenta con acceso a Minecraft Java necesita un perfil de jugador creado.

Usa el identificador público de una aplicación propia registrada como cliente público, habilitada para cuentas personales de Microsoft y autorizada para las API de Minecraft Java. Activa los flujos de cliente público en el registro de Microsoft Entra. Consulta la documentación oficial del flujo. [Microsoft Entra](https://learn.microsoft.com/es-es/entra/identity-platform/v2-oauth2-device-code)

- En el dispositivo, abre **Perfil de jugador → Microsoft**, pega el ID público si el campo está disponible y pulsa **Vincular Microsoft**. Completa el código, vuelve a PRISSMA y guarda el perfil.
- En GitHub Actions, define la variable de repositorio `PRISSMA_MICROSOFT_CLIENT_ID` para incluir el ID público en la APK. Para una compilación local también puedes usar `-PmicrosoftClientId=<uuid>`.
- Para probar la sesión completa, lanza una instancia Java con un perfil vinculado y entra a un servidor que valide cuentas Microsoft. **Cerrar sesión de Microsoft** borra la sesión de PRISSMA. Selecciona **Offline** si quieres jugar con tu nombre local.

El juego obtiene su token directamente desde el almacenamiento cifrado de PRISSMA. Los registros que recoge el puente nativo ocultan ese token.
