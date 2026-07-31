package com.dwinovo.numen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	/** Forge/Fabric container identity. */
	public static final String MOD_ID = "momo_engine";
	public static final String MOD_NAME = "Momo Engine";
	/** Existing assets remain under this namespace during the ABI transition. */
	public static final String RESOURCE_NAMESPACE = "numen_api";
	/** Never strand an existing server's config/numen directory. */
	public static final String LEGACY_CONFIG_DIRECTORY = "numen";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
}
