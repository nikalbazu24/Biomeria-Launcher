# Cuentas Ely.by para Minecraft Java

Abre **Perfil de jugador → Ely.by**, introduce el usuario o correo de Ely.by y vincula la cuenta. Si Ely.by solicita verificación en dos pasos, introduce los seis dígitos de tu autenticador. Guarda el perfil y lanza una instancia de Java. Para verificar la sesión multijugador, entra a un servidor configurado para autenticar con Ely.by y compatible con tu versión y tus mods.

BIOMERIA usa la API de autenticación de Ely.by. Conserva cifrados el token del juego y el identificador del cliente, y verifica o renueva la sesión al abrir Minecraft. La contraseña y el código de verificación se usan durante el inicio de sesión. Al cerrar sesión, BIOMERIA elimina la copia local e intenta revocar el token en Ely.by. [Documentación de Ely.by](https://docs.ely.by/en/minecraft-auth.html)

La APK incluye el binario sin modificar de authlib-injector 1.2.8 bajo AGPLv3 con la excepción authlib-injector. El JAR contiene el texto de licencia y los avisos de sus dependencias. BIOMERIA lo carga como agente Java al elegir Ely.by. [Código fuente de authlib-injector 1.2.8](https://github.com/yushijinhun/authlib-injector/archive/refs/tags/v1.2.8.tar.gz) · [Licencia y excepción](https://github.com/yushijinhun/authlib-injector/blob/v1.2.8/AGPLv3-with-authlib-injector-exception.txt)
