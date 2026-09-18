package com.kitbot.kitrequester.core;

/** Lo que OrderMachine pide hacer. KitRequester las ejecuta en orden. */
public sealed interface Action {
    /** Enviar al servidor: solo "/w SnifferBuddy !kit …" o "/tpy <courier>". */
    record SendCommand(String command) implements Action {}
    /** Empezar a vaciar shulkers en el ender chest al alcance. */
    record Deposit() implements Action {}
    /** Aviso local. {@code alert} = toast + sonido + warning; si no, solo info en el chat local. */
    record Notify(String message, boolean alert) implements Action {}
    /** Añadir un courier aceptado por confianza al ajuste known-couriers. */
    record LearnCourier(String name) implements Action {}
    /** Guardar progress.json. */
    record Save() implements Action {}
    /** Desactivar el módulo. */
    record Disable(String reason) implements Action {}
}
