package com.lance.realtimeobjectrecognition.utils

import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red

fun identifyColor(colorIntValue: Int) =
    CustomColor.values().minBy { calculateSquareDistance(colorIntValue, it) } ?: CustomColor.VOID

fun calculateSquareDistance(color1: Int, color2: Int) =
    ((color1.red - color2.red)*(color1.red - color2.red) +
            (color1.green - color2.green)*(color1.green - color2.green) +
            (color1.blue - color2.blue)*(color1.blue - color2.blue))

fun calculateSquareDistance(color1: Int, colorTemplate: CustomColor) =
    ((color1.red - colorTemplate.red)*(color1.red - colorTemplate.red) +
            (color1.green - colorTemplate.green)*(color1.green - colorTemplate.green) +
            (color1.blue - colorTemplate.blue)*(color1.blue - colorTemplate.blue))

fun getHexColor(colorInt: Int) = String.format("#%06X", (0xFFFFFF and colorInt))

enum class CustomColor(val title: String, val intValue: Int, val red: Int, val green: Int, val blue: Int) {
    VOID("", 0, 0, 0, 0),
    SALMON("salmon", 16416882, 250, 128, 114),
    CRIMSON("crimson", 14423100, 220, 20, 60),
    RED("red", 16711680, 255, 0, 0),
    DARKRED("dark red", 9109504, 139, 0, 0),
    PINK("pink", 16761035, 255, 192, 203),
    DEEPPINK("deep pink", 16716947, 255, 20, 147),
    CORAL("coral", 16744272, 255, 127, 80),
    ORANGERED("orange red", 16729344, 255, 69, 0),
    ORANGE("orange", 16753920, 255, 165, 0),
    YELLOW("yellow", 16776960, 255, 255, 0),
    KHAKI("khaki", 12433259, 189, 183, 107),
    PLUM("plum", 14524637, 221, 160, 221),
    VIOLET("violet", 15631086, 238, 130, 238),
    MAGENTA("magenta", 16711935, 255, 0, 255),
    DARKVIOLET("dark violet", 9699539, 148, 0, 211),
    PURPLE("purple", 8388736, 128, 0, 128),
    INDIGO("indigo", 4915330, 75, 0, 130),
    LIME("lime", 65280, 0, 255, 0),
    LIGHTGREEN("light green", 9498256, 144, 238, 144),
    GREEN("green", 32768, 0, 128, 0),
    OLIVE("olive", 8421376, 128, 128, 0),
    TEAL("teal", 32896, 0, 128, 128),
    CYAN("cyan", 65535, 0, 255, 255),
    TURQUOISE("turquoise", 4251856, 64, 224, 208),
    LIGHTBLUE("light blue", 11393254, 173, 216, 230),
    BLUE("blue", 255, 0, 0, 255),
    DARKBLUE("dark blue", 139, 0, 0, 139),
    TAN("tan", 13808780, 210, 180, 140),
    BROWN("brown", 10824234, 165, 42, 42),
    MAROON("maroon", 8388608, 128, 0, 0),
    WHITE("white", 16777215, 255, 255, 255),
    AZURE("azure", 15794175, 240, 255, 255),
    BEIGE("beige", 16119260, 245, 245, 220),
    LIGHTGREY("light grey", 13882323, 211, 211, 211),
    SILVER("silver", 12632256, 192, 192, 192),
    GREY("grey", 8421504, 128, 128, 128),
    SLATEGREY("slate grey", 7372944, 112, 128, 144),
    BLACK("black", 0, 0, 0, 0)
}