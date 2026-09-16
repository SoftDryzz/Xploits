package com.kitbot.modules;

import com.kitbot.KitBotAddon;
import com.kitbot.core.AutoTpyPolicy;
import com.kitbot.core.ChatEvent;
import com.kitbot.core.ChatPatterns;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

import java.util.List;
import java.util.Set;

/** Acepta al instante las TPA de la lista users y, si se quiere, de los amigos de Meteor. Nunca acepta /tpahere. */
public class AutoTpy extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> users = sgGeneral.add(new StringListSetting.Builder()
        .name("users")
        .description("Jugadores cuya TPA se acepta al instante. Nombres exactos; distinguen mayúsculas.")
        .build()
    );

    private final Setting<Boolean> includeFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("include-friends")
        .description("Aceptar también a los amigos de Meteor (.friends add).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al aceptar o ignorar una TPA.")
        .defaultValue(true)
        .build()
    );

    private final AutoTpyPolicy policy = new AutoTpyPolicy();

    public AutoTpy() {
        super(KitBotAddon.CATEGORY, "auto-tpy", "Acepta al instante las TPA de tu lista y de tus amigos de Meteor.");
    }

    /** Prioridad máxima para ver el mensaje antes de que otros módulos lo modifiquen. */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;
        ChatPatterns.classify(event.getMessage().getString()).ifPresent(chatEvent -> {
            if (!(chatEvent instanceof ChatEvent.Tpa tpa)) return;
            String name = tpa.requester();
            boolean friend = Friends.get().get(name) != null;
            AutoTpyPolicy.Decision decision = policy.decide(name, Set.copyOf(users.get()), friend,
                includeFriends.get(), kitRequesterCouriers(), System.currentTimeMillis());
            switch (decision) {
                case ACCEPT -> {
                    ChatUtils.sendPlayerMsg(ChatPatterns.acceptCommand(name), false);
                    if (notify.get()) info("TPA aceptada de %s.", name);
                }
                case NOT_ALLOWED -> {
                    if (notify.get()) info("TPA ignorada de %s: no está en la lista.", name);
                }
                case DUPLICATE, HANDLED_BY_KIT_REQUESTER, INVALID -> {
                    // Sin aviso: repetición, courier gestionado por KitRequester o nombre vacío.
                }
            }
        });
    }

    private Set<String> kitRequesterCouriers() {
        KitRequester kitRequester = Modules.get().get(KitRequester.class);
        return kitRequester != null && kitRequester.isActive() ? kitRequester.knownCouriers() : Set.of();
    }
}
