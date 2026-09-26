# Cheatsheet — commandes utiles au quotidien

## Interface

```bash
./scripts/can_up.sh 125000     # CAN-Confort (commandes au volant)
./scripts/can_up.sh 500000     # CAN-IS (moteur/ABS — pas pour le volant)
./scripts/can_down.sh
ip -details -statistics link show slcan0
```

## Capture brute

```bash
candump -tA slcan0                      # affichage live, timestamp absolu
candump -tA -L slcan0 > capture.log     # log rejouable
candump -tA -c -c slcan0                # avec couleurs
candump slcan0,131:7FF                  # filtrer ID 0x131
candump slcan0,0:0,#FFFFFFFF            # match ALL avec error frames
```

## Rejouer une capture

```bash
canplayer -I capture.log                 # rejoue tel quel
canplayer -I capture.log -g 1.0          # x1.0 vitesse réelle (par défaut)
```

## Émettre une trame (test)

```bash
cansend slcan0 131#0102030405060708
cansend slcan0 18FEF100#01.02.03.04.05.06.07.08    # 29-bit
```

## Statistiques live

```bash
cansniffer -c slcan0                    # vue type Wireshark texte
canbusload slcan0 125000                # taux occupation bus
```

## Erreurs / debug bus

```bash
ip -details -statistics link show slcan0 | grep -i bus
# bus-off → bitrate faux ou bus pas terminé
# error-passive → des erreurs d'ack, normal sans voiture branchée

dmesg | tail -20                        # erreurs noyau
```

## Wireshark (optionnel, GUI)

```bash
sudo apt install wireshark
wireshark -i slcan0 -k                  # ouvre la capture live
```

## Si tu redémarres le PC

L'interface `slcan0` n'est PAS persistante — il faut relancer `./scripts/can_up.sh`
à chaque session. Pour persister, ajouter une udev rule + service systemd
(pas fait ici par défaut, on reste en setup manuel pour debug).

## Permissions sans sudo (optionnel)

Pour que ton user accède à `/dev/ttyACM0` sans sudo :
```bash
sudo usermod -aG dialout "$USER"
# log out / log in nécessaire ensuite
```
