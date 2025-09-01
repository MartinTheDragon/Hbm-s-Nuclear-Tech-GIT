package com.hbm.config;

import net.minecraftforge.common.config.Configuration;

import java.util.Arrays;

public class RadioConfig {

	public static int maxRange = 64;

	public static boolean networkEnabled = true;
	public static boolean forceSSL = true;
	public static String[] trustedServers = { "https://radio.ntmr.dev:8443/", "https://radio.ntmr.dev/" };

	public static void loadFromConfig(Configuration config) {

		final String CATEGORY_RADIO = CommonConfig.CATEGORY_RADIO;

		maxRange = CommonConfig.createConfigInt(config, CATEGORY_RADIO, "19.00_maxRange", "Maximum range radios can be heard over. Setting this on a server limits it for everyone", maxRange);

		// Network
		networkEnabled = CommonConfig.createConfigBool(config, CATEGORY_RADIO, "19.N00_networkEnabled", "Enables streaming radio over the network", networkEnabled);
		forceSSL = CommonConfig.createConfigBool(config, CATEGORY_RADIO, "19.N01_forceSSL", "Forces network radio connections to be encrypted. You shouldn't disable this and instead ask the radio host to enable encryption.", forceSSL);
		trustedServers = Arrays.stream(CommonConfig.createConfigStringList(config, CATEGORY_RADIO, "19.N02_trustedServers", "Radio server whitelist for which connections can be accepted on multiplayer. For security reasons, the whitelist cannot be disabled.", trustedServers))
				.map(base -> base.endsWith("/") ? base : base + '/').toArray(String[]::new); // People might forget the trailing slash, which is actually vital for trust checks, otherwise people could craft subdomains on their own domains that look like the base URLs
	}
}
