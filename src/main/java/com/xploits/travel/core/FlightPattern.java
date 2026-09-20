package com.xploits.travel.core;

/** Los patrones de vuelo de AutoTravel: el despiste que se interpone entre origen y destino. */
public enum FlightPattern {
    /** Línea recta, sin despiste: un solo waypoint, el destino. */
    RECTO,
    /** Desviación lateral que alterna de lado a paso corto y amplitud pequeña. */
    ZIGZAG,
    /** La misma desviación que ZIGZAG, pero con tramos largos y desvíos amplios. */
    QUIEBRO,
    /** Un tramo final en espiral que se va cerrando sobre el destino. */
    ESPIRAL,
    /** Apunta primero lejos del destino real y corrige después. Se rechaza en autopista. */
    SENUELO
}
