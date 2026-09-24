package com.xploits.autotpy;

import com.xploits.XploitsAddon;
import com.xploits.autotpy.core.AutoTpyPolicy;
import com.xploits.autotpy.core.TpyText;
import com.xploits.shared.Texts;
import com.xploits.kitrequester.KitRequester;
import com.xploits.shared.XploitsModule;
import com.xploits.shared.chat.ChatEvent;
import com.xploits.shared.chat.ChatPatterns;
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

/**
 * Instantly accepts TPAs from the users list and, if wanted, from Meteor friends. It never accepts /tpahere.
 * If KitRequester is also active: its known couriers are handled by KitRequester alone; and a TPA from your
 * list that arrives while KitRequester is waiting for a courier is accepted by AutoTPY even though KitRequester
 * warns that it ignores it (the order is not affected).
 */
public class AutoTpy extends XploitsModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> users = sgGeneral.add(new StringListSetting.Builder()
        .name("users")
        .description(Texts.startupText(TpyText.SETTING_USERS))
        .build()
    );

    private final Setting<Boolean> includeFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("include-friends")
        .description(Texts.startupText(TpyText.SETTING_INCLUDE_FRIENDS))
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description(Texts.startupText(TpyText.SETTING_NOTIFY))
        .defaultValue(true)
        .build()
    );

    private final AutoTpyPolicy policy = new AutoTpyPolicy();

    public AutoTpy() {
        super(XploitsAddon.CATEGORY, "auto-tpy", Texts.startupText(TpyText.MODULE_DESC));
    }

    /** Highest priority, to see the message before other modules change it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;
        ChatPatterns.classify(event.getMessage().getString()).ifPresent(chatEvent -> {
            if (!(chatEvent instanceof ChatEvent.Tpa tpa)) return;
            String name = tpa.requester();
            boolean friend = Friends.get().get(name) != null;
            long now = System.currentTimeMillis();
            AutoTpyPolicy.Decision decision = policy.decide(name, Set.copyOf(users.get()), friend,
                includeFriends.get(), kitRequesterCouriers(), now);
            switch (decision) {
                case ACCEPT -> {
                    ChatUtils.sendPlayerMsg(ChatPatterns.acceptCommand(name), false);
                    if (notify.get()) info(TpyText.ACCEPTED, "name", name);
                }
                case NOT_ALLOWED -> {
                    if (notify.get() && policy.shouldReportIgnored(name, now)) info(TpyText.IGNORED, "name", name);
                }
                case DUPLICATE, HANDLED_BY_KIT_REQUESTER, INVALID -> {
                    // No notice: a repeat, a courier handled by KitRequester or an empty name.
                }
            }
        });
    }

    /**
     * The users list, for whoever needs to know who is on our side. auto-pvp reads it so as not to
     * attack them, and that is why it is returned whether this module is on or off: the list says
     * whom you trust, not which module is running.
     */
    public Set<String> users() {
        return Set.copyOf(users.get());
    }

    @Override
    public String activity() {
        return Texts.render(TpyText.NOW_LISTED, "count", users().size());
    }

    private Set<String> kitRequesterCouriers() {
        KitRequester kitRequester = Modules.get().get(KitRequester.class);
        return kitRequester != null && kitRequester.isActive() ? kitRequester.knownCouriers() : Set.of();
    }
}
