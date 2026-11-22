# Starling para Android ARM64

El código de `java/` y los recursos de `res/` proceden de [Starling v0.9.0](https://github.com/starling-client/starling/tree/ff303502a4f7982551a0e0d3622d9268efe9e829/packaging/android). Se conserva el paquete Java original porque las bibliotecas nativas lo usan en JNI. PRISSMA registra las actividades dentro de `com.prissma.launcher` y las ejecuta en procesos propios. En `StarlingActivity` se utiliza almacenamiento privado y se delegan las actualizaciones de la APK a PRISSMA.

Gradle verifica el SHA-256 del artefacto ARM64 de la versión y extrae sus ELF y sus recursos de arranque. La APK resultante incluye sus avisos de licencia en `assets/starling/licenses/`. Los recursos de Minecraft Java 26.3 se preparan en el dispositivo con el flujo original del motor.

Cada tarjeta de Rust pasa su perfil a las actividades de juego, importación y diagnóstico. `NativeActivity` entrega al motor el directorio que devuelve `getFilesDir()`. Las tarjetas nuevas usan `files/starling-instances/<id SHA-256>` y la tarjeta original `rust-120fps` conserva el directorio que usaba antes. Si se elige otra tarjeta mientras Starling está abierto, el inicio espera el cierre del proceso de juego antes de cargar el nuevo perfil. El proveedor de documentos separa las rutas y expone únicamente los archivos del jugador.

En una consola Android ARM64, abre la tarjeta de Rust con **Jugar**, completa la preparación inicial y entra a un mundo. Cierra el juego y comprueba que puedes volver a iniciarlo desde la biblioteca. Para verificar el Taller, abre **Rust → Taller** e importa el paquete de ejemplo [`coords_hud-0.1.0.hmod`](https://github.com/starling-client/starling/raw/v0.9.0/crates/horizon-mod-package/tests/index/coords_hud-0.1.0.hmod). El resultado aparece en el taller del motor.

Para comprobar el aislamiento, crea dos tarjetas de Rust con nombres distintos, guarda un mundo en una e importa un `.hmod` desde su Taller. Abre la otra tarjeta y revisa su lista de mundos y sus mods. Vuelve a la primera y verifica que ambos se conservan. Prueba también la tarjeta original si ya tenías partidas en Starling.
