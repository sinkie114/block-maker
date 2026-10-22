package com.sinkie114.blockmaker.client;

/** Every editor screen exposes its session; the session stays alive only while such a screen is open. */
public interface BlockLayer { BlockSession session(); }
