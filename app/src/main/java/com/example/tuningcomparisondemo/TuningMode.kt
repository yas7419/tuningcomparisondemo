package com.example.tuningcomparisondemo

enum class TuningMode { JUST, EQUAL }
enum class ScaleMode { MAJOR, MINOR }

enum class ButtonLayoutMode {
    INSTRUMENT_C_BOTTOM, // 楽器の記譜Cを一番下
    KEY_ROOT_BOTTOM      // 調の主音を一番下
}

// SeventhMode
enum class SeventhMode {
    CLASSIC,  // クラシック系短7度 (9/5)
    HARMONIC  // 自然7度 (7/4)
}