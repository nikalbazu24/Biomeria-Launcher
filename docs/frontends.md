# Biomeria para frontends

Los frontends (Cocoon, iiSU, Díptico…) pueden leer el catálogo y las estadísticas de Biomeria y abrir un mundo directamente.

## Abrir un mundo
Enlace: `biomeria://launch?instance_id=<id>` (también acepta `mchub://launch`).

## ContentProvider
Authority: `com.biomeria.launcher.provider`

### `content://com.biomeria.launcher.provider/instances`
Una fila por mundo: `instance_id`, `title`, `engine`, `mc_version`, `loader`, `playtime_seconds` (tiempo real jugado), `deep_link`.

### `content://com.biomeria.launcher.provider/stats`
Una fila por mundo jugado: `instance_id`, `title`, `seconds_total`, `seconds_7d`, `seconds_30d`, `sessions`, `last_played_ms`.

### `content://com.biomeria.launcher.provider/stats/summary`
Una sola fila con todo junto: `seconds_total`, `seconds_7d`, `seconds_30d`, `sessions`, `best_day_seconds`, `best_day_ms`.

## Archivo `stats.json`
Para quien prefiera leer un archivo: `Android/data/com.biomeria.launcher/files/BIOMERIA/stats.json`. Se actualiza al abrir Biomeria y al terminar cada partida.

```json
{
  "version": 1,
  "generated_ms": 1763900000000,
  "total":        { "seconds": 0, "sessions": 0, "best_day_seconds": 0, "best_day_ms": null },
  "last_7_days":  { "...": "misma forma" },
  "last_30_days": { "...": "misma forma" },
  "worlds": [
    { "instance_id": "...", "title": "...", "total": {}, "last_7_days": {}, "last_30_days": {}, "last_played_ms": 0 }
  ]
}
```

Solo se cuenta el juego de Java. Los tiempos están en segundos.
