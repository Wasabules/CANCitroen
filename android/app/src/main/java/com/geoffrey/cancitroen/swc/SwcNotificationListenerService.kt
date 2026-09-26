package com.geoffrey.cancitroen.swc

import android.service.notification.NotificationListenerService

/**
 * Service stub vide — sert uniquement à exposer l'app dans la liste
 * "Accès aux notifications" du système. Une fois l'utilisateur a accordé
 * l'accès, [SteeringWheelHandler] peut appeler [MediaSessionManager.getActiveSessions]
 * et donc cibler directement la session média active (Android Auto, Spotify, etc.)
 * pour les boutons SUIVANT/PRÉCÉDENT/PLAY-PAUSE.
 *
 * On n'écoute aucune notification dans ce service — il existe juste pour le permission flag.
 */
class SwcNotificationListenerService : NotificationListenerService()
