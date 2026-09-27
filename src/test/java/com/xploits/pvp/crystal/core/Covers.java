package com.xploits.pvp.crystal.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The coverage matrix: the exposed settings a test proves behave as Meteor's CrystalAura with a non-default
 * value and at the boundary. {@code CrystalSettingsCoverageGuardTest} fails when a setting of
 * {@link CrystalSetting} has no test marked with it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Covers {
    CrystalSetting[] value();
}
