package com.example.tuningcomparisondemo

enum class InstrumentKey(val semitoneShift: Int) {
    C(0),      // C管
    Bb(-2),    // B♭管（全音下げ）
    Eb(3),     // E♭管（長6度上げ）
    F(5),      // F管（完全5度上げ）
//    A(  -3)      // A管（短３度下げ）
    A(  -3)      // A管（短３度下げ）
}