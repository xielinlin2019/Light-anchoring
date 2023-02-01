# Light-anchoring



## Motivation
--------
Existing anchoring techniques like QR tags have limited ranges and are easy to spoof. Light based anchors (LEDs) on the other hand are more robust, not obtrusive and not easy to spoof for information. They can also work with smart phone cameras which is a plus with the increased spread of mobile phones. This advantages over other technologies motivated us to work on this project.

## Design Goals
--------
Create a light-based anchor to precisely overlay content on smartphone screen.
To implement all the anchoring functions, and to explore more features of as much as possible.

## Deliverables
--------
Implement hardware prototype for LED encoding.

Receive LED encoded patterns on smartphone camera and decode the pattern (encoder ID).

Precisely overlay encoder ID on smartphone screen.

## System Blocks
--------
![157761639514746_ pic](https://user-images.githubusercontent.com/50798240/146076767-48db1539-90ec-496c-bad4-c9436eb7f591.jpg)


## HW/SW requirements
--------
Hardware: LEDs, Adafruit Feather nRF52 Bluefruit LE - nRF52832, smartphone
Software: Arduino IDE, Android Studio, Kotlin

## References
--------
1. [All that GLITTERs: Low-Power Spoof-Resilient Optical Markers for Augmented Reality, IPSN’20](https://users.ece.cmu.edu/~vsekar/assets/pdf/ipsn20_glitter.pdf)

2. [DeepLight: Robust & Unobtrusive Real-time Screen-Camera Communication for Real-World Displays, IPSN’21](https://arxiv.org/pdf/2105.05092.pdf)
