package com.hbm.sound;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.hbm.config.RadioConfig;
import com.hbm.main.MainRegistry;
import com.hbm.main.ServerProxy;
import com.hbm.util.Tuple;
import com.hbm.util.fauxpointtwelve.BlockPos;
import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import paulscode.sound.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.sound.sampled.AudioFormat;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// TODO logging
@ParametersAreNonnullByDefault
public final class Radio {
	private Radio() {}

	/**
	 * Official radio server base URL. By default, any supplied channel URLs will be checked to have this base string and rejected otherwise.
	 */
	public static final String RADIO_SERVER = "https://radio.ntmr.dev:8443/";
	public static final String CHANNEL_NTM_1 = RADIO_SERVER + "ntm-1-nolive.ogg";

	// Source Position -> Channel URL
	private static final Map<BlockPos, String> activeChannels = new HashMap<>();
	// Channel URL + Source Position Hash -> UUID String
	private static final BiMap<String, String> sourceNames = HashBiMap.create();

	/**
	 * Switches the radio channel for the specified position.
	 * Automatically stops and starts audio sources.
	 *
	 * @param pos Audio source position, or null for global background music
	 * @param channel URL to the audio resource, or null to stop playing
	 * @param volume Initial volume [0F,1F]
	 * @param range Maximum hearing distance if the specified pos is not null
	 * @return The sound name used in the sound system, if a new sound was played
	 * @see Radio#getHeardMusicInfo
	 */
	@Nullable
	public static String switchRadioChannel(@Nullable BlockPos pos, @Nullable String channel, float volume, int range) {
		if(range <= 0) return null;

		// Make sure we are always talking about the same String everywhere,
		// as well as getting rid of any URL hacks people may enter
		String currentChannel = normalizeChannelName(activeChannels.get(pos));
		channel = normalizeChannelName(channel);
		if(currentChannel == null && channel == null) return null;

		if(currentChannel == null) {
			return startPlaying(pos, channel, volume, range);
		} else if(channel == null) {
			stopPlaying(pos, currentChannel);
		} else if(!currentChannel.equals(channel)) {
			stopPlaying(pos, currentChannel);
			return startPlaying(pos, channel, volume, range);
		}

		return null;
	}

	// Distance = Volume * AttFac
	private static final float ATTENUATION_FACTOR = 8F;

	@Nullable
	private static String startPlaying(@Nullable BlockPos pos, String channel, float volume, int range) {
		URL url = getChannelURL(channel);
		if(url == null) return null;

		if(Arrays.stream(RadioConfig.trustedServers).noneMatch(channel::startsWith)) {
			MainRegistry.logger.warn("\"{}\" is not a trusted radio channel, change the config if you want to add its server base URL", channel);
			displayTooltip(EnumChatFormatting.RED + "UNTRUSTED STATION (SEE LOG)");
			return null;
		}

		// Append our custom streaming codec identifier, and only to supported Vorbis resources
		int lastSlashIndex = channel.lastIndexOf('/');
		String identifier = channel.substring(lastSlashIndex) + Codecs.fauxFileFor(Codecs.NTM_ANY_STREAM);

		// Two radio channels can't be sourced simultaneously at a given position, but audio sources may never have the
		// same source name in the sound library, so we additionally store a universally unique identifier
		String sourceKey = channel + Objects.hashCode(pos);
		String sourceName = UUID.randomUUID().toString();

		// If there's for some reason still an audio source at our position, stop and/or remove it
		if (sourceNames.containsKey(sourceKey)) {
			stopPlaying(pos, channel);
		}

		SoundSystem sndSystem = getSoundSystem();

		if (pos == null) {
			sndSystem.backgroundMusic(sourceName, url, identifier, true);
			sndSystem.setVolume(sourceName, volume);
		} else {
			sndSystem.newStreamingSource(
					true, sourceName,
					url, identifier, true,
					(float) pos.getX(), (float) pos.getY(), (float) pos.getZ(),
					ISound.AttenuationType.LINEAR.getTypeInt(), range
			);
			sndSystem.setVolume(sourceName, volume);
			sndSystem.play(sourceName);
		}

		sourceNames.put(sourceKey, sourceName);
		activeChannels.put(pos, channel);

		return sourceName;
	}

	private static void stopPlaying(@Nullable BlockPos pos, String currentChannel) {
		String sourceKey = currentChannel + Objects.hashCode(pos);
		String sourceName = sourceNames.get(sourceKey);
		getSoundSystem().stop(sourceName);
		sourceNames.remove(sourceKey);
		activeChannels.remove(pos);
		currentlyPlayingSongs.remove(currentChannel);
		resumeVolumes.remove(sourceName);
	}

	@Nullable
	private static String normalizeChannelName(@Nullable String channel) {
		URL url = getChannelURL(channel);
		return url == null ? null : url.toString();
	}

	@Nullable
	private static URL getChannelURL(@Nullable String channel) {
		if(channel == null) return null;

		// Make sure we're connecting using SSL if it is enforced in config
		if(RadioConfig.forceSSL && !channel.startsWith("https://")) {
			if(channel.startsWith("http://")) {
				MainRegistry.logger.warn("Changing \"{}\" to use https because of SSL being forced, use the proper https URL to remove this warning", channel);
				channel = channel.replace("http://", "https://");
			} else {
				MainRegistry.logger.error("\"{}\" uses an unrecognized protocol", channel);
				displayTooltip(EnumChatFormatting.RED + "UNRECOGNIZED PROTOCOL");
				return null;
			}
		}

		try {
			return new URL(channel);
		} catch (MalformedURLException e) {
			MainRegistry.logger.error("Channel URL \"{}\" is malformed", channel, e);
			displayTooltip(EnumChatFormatting.RED + "MALFORMED CHANNEL URL");

			return null;
		}
	}

	public static void changeVolume(String sourceName, float volume) {
        getSoundSystem().setVolume(sourceName, volume);
	}

	public static void changeRange(String sourceName, int range) {
		getSoundSystem().setDistOrRoll(sourceName, range);
	}

	// Source Name -> Volume
	private static final Map<String, Float> resumeVolumes = new HashMap<>();

	/**
	 * Changes the volume of all radio sound sources to 0.
	 * Sound sources aren't truly paused since their connection might time out.
	 * Call {@link #resume()} to undo this operation.
	 */
	public static void silence() {
		SoundSystem sndSystem = getSoundSystem();
		for(String sourceName : sourceNames.values()) {
			float volume = sndSystem.getVolume(sourceName);
			resumeVolumes.put(sourceName, volume);
			changeVolume(sourceName, 0F);
		}
	}

	/**
	 * Changes the volume of each radio sound source back to its value before {@link #silence()} was called.
	 */
	public static void resume() {
		for(Map.Entry<String, Float> entry : resumeVolumes.entrySet()) {
			changeVolume(entry.getKey(), entry.getValue());
		}
		resumeVolumes.clear();
	}

	public static void stopAll() {
		SoundSystem sndSystem = getSoundSystem();
		for(String sourceName : sourceNames.values()) {
			sndSystem.stop(sourceName);
		}
		sourceNames.clear();
		activeChannels.clear();
		currentlyPlayingSongs.clear();
		resumeVolumes.clear();
		previousRadioSongs.clear();
	}

	/*
	 * Stops all sound sources for a specific radio channel until the
	 * channel is reactivated again, but keeps the connections alive.
	 * Note that the connection may time out if culled for too long.
	 * This is mainly used along with reactivate to get the
	 * Sound System to reset the OpenAL channel.
	 */
	private static void cull(String channel) {
		String normalizedChannel = normalizeChannelName(channel);
		for(Map.Entry<BlockPos, String> entry : activeChannels.entrySet()) {
			if (!entry.getValue().equals(normalizedChannel))
				continue;
			String sourceKey = channel + Objects.hashCode(entry.getKey());
			String sourceName = sourceNames.get(sourceKey);
			getSoundSystem().cull(sourceName);
		}
	}

	private static void reactivate(String channel) {
		String normalizedChannel = normalizeChannelName(channel);
		for(Map.Entry<BlockPos, String> entry : activeChannels.entrySet()) {
			if (!entry.getValue().equals(normalizedChannel))
				continue;
			String sourceKey = channel + Objects.hashCode(entry.getKey());
			String sourceName = sourceNames.get(sourceKey);
			getSoundSystem().activate(sourceName);
		}
	}

	// Channel URL -> "$ARTIST - $TITLE", "$TITLE" or "UNKNOWN"
	// Values are updated automatically by our custom streaming codec
	public static final Map<String, String> currentlyPlayingSongs = new HashMap<>();

	/**
	 * Filters currently playing songs for those that can be heard from the specified position.
	 * @param pos Listener position
	 * @return Channel URL -> "$ARTIST - $TITLE", "$TITLE" or "UNKNOWN"
	 */
	public static Map<String, String> getHeardMusicInfo(@Nonnull Vec3 pos) {
		Map<String, String> heardMusic = new HashMap<>();
		SoundSystem sndSystem = getSoundSystem();

		for(Map.Entry<String, String> entry : currentlyPlayingSongs.entrySet()) {
			String channelName = entry.getKey();
			String musicInfo = entry.getValue();

			// Why do people praise the Java Stream API so much? It may be leagues better than normal collection operations,
			// but it's comparatively low performance, still rather awful to use due to lack of some operations and
			// atrocious to look at (not only but mostly due to Java syntax)...
			// I wani have Kotlin standard library :(
			boolean isHeard = activeChannels.entrySet().stream()
					.filter(blockPosChannelEntry -> blockPosChannelEntry.getValue().equals(channelName)) // Find active sound sources of the current channel
					.map(blockPosChannelEntry -> new Tuple.Pair<>(blockPosChannelEntry.getKey(), sndSystem.getVolume(sourceNames.get(channelName + Objects.hashCode(blockPosChannelEntry.getKey()))) * ATTENUATION_FACTOR)) // FIXME
					.anyMatch(pair -> pair.getKey() == null || pos.squareDistanceTo(pair.getKey().getX(), pair.getKey().getY(), pair.getKey().getZ()) <= pair.getValue() * pair.getValue());

			if (isHeard) {
				heardMusic.put(channelName, musicInfo);
			}
		}

		return heardMusic;
	}

	// Channel URL -> "$ARTIST - $TITLE", "$TITLE" or "UNKNOWN"
	private static final Map<String, String> previousRadioSongs = new HashMap<>();

	public static void updateMusicInfoDisplay() {
		Map<String, String> currentlyHeardRadioChannels = Radio.getHeardMusicInfo(Minecraft.getMinecraft().thePlayer.getPosition(1F));
		for(Map.Entry<String, String> entry : currentlyHeardRadioChannels.entrySet()) {
			String channel = entry.getKey();
			String song = entry.getValue();

			if(song.equals(previousRadioSongs.get(channel)))
				continue;

			previousRadioSongs.put(channel, song);
			Radio.displayTooltip(song);
		}
	}

	public static void displayTooltip(String msg) {
		String message = EnumChatFormatting.BLUE + "Radio: " + EnumChatFormatting.RESET + msg;
		MainRegistry.proxy.displayTooltip(message, 15_000, ServerProxy.ID_RADIO);
	}

	private static final List<String> pendingConnections = new ArrayList<>();

	public static List<String> getPendingConnections() {
		return new ArrayList<>(pendingConnections);
	}

	public static boolean isConnectionPending(String channel) {
		return pendingConnections.contains(normalizeChannelName(channel));
	}

	public static boolean isConnectionPending(@Nullable BlockPos pos) {
		String channel = activeChannels.get(pos);
		if(channel == null)
			return false;
		return isConnectionPending(channel);
	}

	public static boolean isConnected(String channel) {
		return activeChannels.containsValue(normalizeChannelName(channel)) && !isConnectionPending(channel);
	}

	public static boolean isConnected(@Nullable BlockPos pos) {
		String channel = activeChannels.get(pos);
		if(channel == null)
			return false;
		return isConnected(channel);
	}

	private static SoundSystem getSoundSystem() {
		return Minecraft.getMinecraft().getSoundHandler().sndManager.sndSystem;
	}

	public static class Codecs {
		private Codecs() {}

		/*
		 * Stream file name extensions are used to "fool" the Sound System to using our custom streaming codecs.
		 * The extensions mustn't contain any part of real file extensions like "ogg", otherwise it'll confuse
		 * the Sound System's RegEx search.
		 */
		public static final String NTM_ANY_STREAM = "ntmstream"; // Use this to connect
		public static final String NTM_VORBIS = "ntmstreamvorbis";

		// The RegEx in the Sound System expects a dot in the file name
		public static String fauxFileFor(String extension) {
			return "." + extension;
		}
	}

	/*
	 * Handles asynchronously loading sound sources and dynamically switches to them when their connection is established.
	 * The entire reason we need this is that Mojang made it so the whole game hangs when
	 * the SoundLibrary's command queue thread is busy performing an operation under a mutex lock.
	 * So the entire main client thread starts hanging and waits for the lock to open so it can find out
	 * through Mojang's custom implementation of the sound thread if music records are currently playing :/
	 * And since connecting to a remote web server when initializing a sound stream takes an arbitrarily
	 * long amount of time, we have to ensure the game doesn't hang while that's happening.
	 */
	public static class ProxyStream implements ICodec {
		public static final Map<String, URLConnection> connectionPool = new HashMap<>();
		private static final ExecutorService connectionService = Executors.newCachedThreadPool();

		private static final boolean GET = false;
		private static final boolean SET = true;
		private static final boolean XXX = false;

		private ICodec delegateStream;
		private URL url;

		private boolean endOfStream = false;
		private boolean initializing = false;

		private final SoundSystemLogger logger;

		public ProxyStream() {
			logger = SoundSystemConfig.getLogger();
		}

		@Override
		public void reverseByteOrder(boolean b) {}

		@Override
		public boolean initialize(URL url) {
			if(initialized() && this.url == url)
				return true;

			if(initializing(GET, XXX))
				return true;

			this.url = url;

			URLConnection urlConnection;

			try {
				urlConnection = url.openConnection();
				urlConnection.setConnectTimeout(10_000);
				urlConnection.setReadTimeout(10_000);
			} catch(IOException ioe) {
				errorMessage("Unable to create a UrlConnection in method 'initialize'.");
				printStackTrace(ioe);
				cleanup();
				return false;
			}

			pendingConnections.add(url.toString());

			connectionService.execute(() -> {
				InputStream stream;
				try {
					stream = urlConnection.getInputStream();
				} catch (FileNotFoundException notFoundException) {
					message("Connecting to '" + url + "' returned 404 (not found)");
					cleanup();
					stream = null;
				} catch (IOException ioe) {
					errorMessage("Unable to acquire inputstream in method 'initialize'.");
					printStackTrace(ioe);
					cleanup();
					stream = null;
				}
				initializedCallback(url, urlConnection, stream);
			});

			initializing(SET, true);

			return true;
		}

		// Called after the connection to the radio server is established
		// TODO thread-safety
		private void initializedCallback(URL url, URLConnection connection, @Nullable InputStream stream) {
			String urlString = url.toString();
			pendingConnections.remove(urlString);
			connectionPool.put(urlString, connection);
			endOfStream(SET, true);

			if(stream == null)
				return;

			ICodec suitableCodec;
			switch(connection.getContentType()) {
				case "application/ogg":
				case "audio/ogg": suitableCodec = SoundSystemConfig.getCodec(Codecs.fauxFileFor(Codecs.NTM_VORBIS)); break;
				default:
					// TODO fallback to 3rd party codecs
					errorMessage("No suitable codec available to decode audio stream from " + url);
					return;
			}
			delegateStream = suitableCodec;
			delegateStream.initialize(url);

			// We do this to get the Sound System to re-read #getAudioFormat(), since the audio format
			// sent by the server likely differs from our default.
			Radio.cull(urlString);
			Radio.reactivate(urlString);
		}

		@Override
		public boolean initialized() {
			if(delegateStream == null)
				return false;

			return delegateStream.initialized();
		}

		@Override
		public SoundBuffer read() {
			if(delegateStream == null)
				return new SoundBuffer(new byte[SoundSystemConfig.getStreamingBufferSize()], getAudioFormat());

			return delegateStream.read();
		}

		@Override
		public SoundBuffer readAll() {
			if(delegateStream == null)
				return new SoundBuffer(new byte[SoundSystemConfig.getStreamingBufferSize()], getAudioFormat());

			return delegateStream.readAll();
		}

		@Override
		public boolean endOfStream() {
			if(delegateStream == null)
				return endOfStream(GET, XXX);

			return delegateStream.endOfStream();
		}

		@Override
		public void cleanup() {
			URLConnection connection = connectionPool.remove(url.toString());
			if(connection != null) {
				/*
				 * All we do is disconnect gracefully from the remote radio server if we indeed have
				 * a web connection over HTTP, since we assume the delegateStream will close the
				 * InputStream properly no matter where it comes from and fetching an InputStream from
				 * the URLConnection here might result in the creation of a new one.
				 */
				if(connection instanceof HttpURLConnection)
					((HttpURLConnection) connection).disconnect();
			}

			if(delegateStream != null) {
				delegateStream.cleanup();
			}
			delegateStream = null;
		}

		@Override
		public AudioFormat getAudioFormat() {
			if(delegateStream == null)
				return new AudioFormat(44_100F, 16, 1, true, false);

			return delegateStream.getAudioFormat();
		}

		private synchronized boolean initializing(boolean action, boolean value) {
			if(action == SET)
				initializing = value;
			return initializing;
		}

		private synchronized boolean endOfStream(boolean action, boolean value) {
			if(action == SET)
				endOfStream = value;
			return endOfStream;
		}

		private void errorMessage(String message) {
			logger.errorMessage("Radio.CodecJOrbisStream", message, 0);
		}

		private void message(String message) {
			logger.message(message, 0);
		}

		private void printStackTrace(Exception e) {
			logger.printStackTrace(e, 1);
		}
	}

	// TODO audio format can change between songs
	public static class CodecJOrbisStream implements ICodec {
		private static final boolean GET = false;
		private static final boolean SET = true;
		private static final boolean XXX = false;

		private URL url;
		private InputStream inputStream;
		private AudioFormat audioFormat;

		private boolean endOfStream = false;
		private boolean initialized = false;
		private boolean chained = false;

		private byte[] buffer = null;
		private int bufferSize;

		private int count = 0;
		private int index = 0;

		private int convertedBufferSize;
		private byte[] convertedBuffer = null;

		private float[][][] pcmInfo;
		private int[] pcmIndex;

		private final Packet joggPacket = new Packet();
		private final Page joggPage = new Page();
		private StreamState joggStreamState = new StreamState();
		private SyncState joggSyncState = new SyncState();
		private DspState jorbisDspState = new DspState();
		private Block jorbisBlock = new Block(jorbisDspState);
		private final Comment jorbisComment = new Comment();
		private Info jorbisInfo = new Info();

		private final SoundSystemLogger logger;

		public CodecJOrbisStream() {
			logger = SoundSystemConfig.getLogger();
		}

		@Override
		public void reverseByteOrder(boolean b) {}

		@Override
		public boolean initialize(URL url) {
			if(initialized() && inputStream != null && this.url == url)
				return true;

			initialized(SET, false);

			if(joggStreamState != null) joggStreamState.clear();
			if(jorbisBlock != null) jorbisBlock.clear();
			if(jorbisDspState != null) jorbisDspState.clear();
			if(jorbisInfo != null) jorbisInfo.clear();
			if(joggSyncState != null) joggSyncState.clear();

			if(inputStream != null) {
				try {
					inputStream.close();
				} catch(IOException ignored) {}
			}

//			this.bufferSize = SoundSystemConfig.getStreamingBufferSize() / 2;
            this.bufferSize = 4096 * 4;

			buffer = null;
			count = 0;
			index = 0;

			joggStreamState = new StreamState();
			jorbisBlock = new Block(jorbisDspState);
			jorbisDspState = new DspState();
			jorbisInfo = new Info();
			joggSyncState = new SyncState();

			this.url = url;

			URLConnection urlConnection;

			if(ProxyStream.connectionPool.containsKey(url.toString()))
				urlConnection = ProxyStream.connectionPool.get(url.toString());
			else try {
				urlConnection = url.openConnection();
				urlConnection.setConnectTimeout(10_000);
				urlConnection.setReadTimeout(10_000);
			} catch(IOException ioe) {
				errorMessage("Unable to create a UrlConnection in method 'initialize'.");
				printStackTrace(ioe);
				cleanup();
				return false;
			}

			try {
				inputStream = urlConnection.getInputStream();
			} catch (FileNotFoundException notFoundException) {
				Minecraft.getMinecraft().func_152344_a(() -> Radio.displayTooltip(EnumChatFormatting.RED + "404 NOT FOUND"));
				message("Connecting to '" + url + "' returned 404 (not found)");
//				printStackTrace(notFoundException);
				cleanup();
				return false;
			} catch (IOException ioe) {
				errorMessage("Unable to acquire inputstream in method 'initialize'.");
				printStackTrace(ioe);
				cleanup();
				return false;
			}

			endOfStream(SET, false);

			joggSyncState.init();
			joggSyncState.buffer(bufferSize);
			buffer = joggSyncState.data;

			try {
				if(!readHeader()) {
					errorMessage("Error reading the header");
					return false;
				}
			} catch(IOException ioe) {
				errorMessage("Error reading the header");
				return false;
			}

			convertedBufferSize = bufferSize * 2;

			jorbisDspState.synthesis_init(jorbisInfo);
			jorbisBlock.init(jorbisDspState);

			int channels = jorbisInfo.channels;
			int rate = jorbisInfo.rate;

			audioFormat = new AudioFormat((float) rate, 16, channels, true, false);
			pcmInfo = new float[1][][];
			pcmIndex = new int[jorbisInfo.channels];

			initialized(SET, true);

			return true;
		}

		@Override
		public boolean initialized() {
			return initialized(GET, XXX);
		}

		@Override
		@Nullable
		public SoundBuffer read() {
			byte[] returnBuffer = null;

			while(returnBuffer == null || returnBuffer.length < SoundSystemConfig.getStreamingBufferSize()) {
				if(returnBuffer == null)
					returnBuffer = readBytes();
				else
					returnBuffer = appendByteArrays(returnBuffer, readBytes());

				if(count == -1)
					break;

				if(endOfStream()) {
					try {
						if (!readHeader()) {
							return null;
						}
					} catch (IOException e) {
						return null;
					}
				}
			}

			return new SoundBuffer(returnBuffer, audioFormat);
		}

		@Override
		@Nullable
		public SoundBuffer readAll() {
			byte[] returnBuffer = null;

			while(!endOfStream(GET, XXX)) {
				if(returnBuffer == null)
					returnBuffer = readBytes();
				else
					returnBuffer = appendByteArrays(returnBuffer, readBytes());

				if(count == -1)
					break;
			}

			if(returnBuffer == null) {
				return null;
			}

			return new SoundBuffer(returnBuffer, audioFormat);
		}

		@Override
		public boolean endOfStream() {
			return endOfStream(GET, XXX);
		}

		@Override
		public void cleanup() {
			joggStreamState.clear();
			jorbisBlock.clear();
			jorbisDspState.clear();
			jorbisInfo.clear();
			joggSyncState.clear();

			if(inputStream != null) {
				try {
					inputStream.close();
				} catch(IOException ignored) {}
			}

			joggStreamState = null;
			jorbisBlock = null;
			jorbisDspState = null;
			jorbisInfo = null;
			joggSyncState = null;
			inputStream = null;
		}

		@Override
		public AudioFormat getAudioFormat() {
			return audioFormat;
		}

		private boolean readHeader() throws IOException {
			index = joggSyncState.buffer(bufferSize);
			int bytes = inputStream.read(joggSyncState.data, index, bufferSize);
			if(bytes == -1)
				return false;
			joggSyncState.wrote(bytes);

			endOfStream(SET, false);

			if (chained) {
				chained = false;
			} else if(joggSyncState.pageout(joggPage) != 1) {
				// Finished reading the entire file:
				if(bytes < bufferSize)
					return true;

				errorMessage("Ogg header not recognized in method 'readHeader'.");
				return false;
			}

			joggStreamState.init(joggPage.serialno());
			joggStreamState.reset();

			jorbisInfo.init();
			jorbisComment.init();

			if(joggStreamState.pagein(joggPage) < 0) {
				errorMessage("Problem with first Ogg header page in method 'readHeader'.");
				return false;
			}

			if(joggStreamState.packetout(joggPacket) != 1) {
				errorMessage("Problem with first Ogg header packet in method 'readHeader'.");
				return false;
			}

			if(jorbisInfo.synthesis_headerin(jorbisComment, joggPacket) < 0) {
				errorMessage("File does not contain Vorbis header in method 'readHeader'.");
				return false;
			}

			int i = 0;
			while(i < 2) {
				while(i < 2) {
					int result = joggSyncState.pageout(joggPage);
					if(result == 0)
						break;
					if(result == 1) {
						joggStreamState.pagein(joggPage);
						while(i < 2) {
							result = joggStreamState.packetout(joggPacket);
							if(result == 0)
								break;

							if(result == -1) {
								errorMessage("Secondary Ogg header corrupt in method 'readHeader'.");
								return false;
							}

							jorbisInfo.synthesis_headerin(jorbisComment, joggPacket);
							i++;
						}
					}
				}
				index = joggSyncState.buffer(bufferSize);
				buffer = joggSyncState.data;
				bytes = inputStream.read(joggSyncState.data, index, bufferSize);
				if(Math.max(bytes, 0) == 0 && i < 2) {
					errorMessage("End of file reached before finished readingOgg header in method 'readHeader'");
					return false;
				}

				joggSyncState.wrote(bytes);
			}

			String artist = jorbisComment.query("ARTIST");
			String title = jorbisComment.query("TITLE");
			String infoString = title != null
					? artist != null ? artist + " - " + title : title
					: "UKNOWNN";
			currentlyPlayingSongs.put(url.toString(), infoString);

			return true;
		}

		private byte[] readBytes() {
			if(!initialized(GET, XXX))
				return null;

			if(endOfStream(GET, XXX))
				return null;

			if(convertedBuffer == null)
				convertedBuffer = new byte[convertedBufferSize];
			byte[] returnBuffer = null;

			float[][] pcmf;
			int samples, bout, ptr, mono, val, i, j;

			switch(joggSyncState.pageout(joggPage)) {
				case(0):
				case(-1): break;
				default: {
					joggStreamState.pagein(joggPage);
					if(joggPage.granulepos() == 0) {
						chained = true;
						endOfStream(SET, true);
						return null;
					}

					processPackets: while(true) {
						switch(joggStreamState.packetout(joggPacket)) {
							case(0): break processPackets;
							case(-1): break;
							default: {
								if(jorbisBlock.synthesis(joggPacket) == 0)
									jorbisDspState.synthesis_blockin(jorbisBlock);

								while((samples=jorbisDspState.synthesis_pcmout(pcmInfo, pcmIndex)) > 0) {
									pcmf = pcmInfo[0];
									bout = Math.min(samples, convertedBufferSize);
									for(i = 0; i < jorbisInfo.channels; i++) {
										ptr = i * 2;
										mono = pcmIndex[i];
										for(j = 0; j < bout; j++) {
											val = (int) (pcmf[i][mono + j] * 32767.);
											if(val > 32767)
												val = 32767;
											if(val < -32768)
												val = -32768;
											if(val < 0)
												val = val | 0x8000;
											convertedBuffer[ptr] = (byte) (val);
											convertedBuffer[ptr+1] = (byte) (val>>>8);
											ptr += 2 * (jorbisInfo.channels);
										}
									}
									jorbisDspState.synthesis_read(bout);
									returnBuffer = appendByteArrays(returnBuffer, convertedBuffer, 2 * jorbisInfo.channels * bout);
								}
							}
						}
					}

					if(joggPage.eos() != 0)
						endOfStream(SET, true);
				}
			}

			if(!endOfStream(GET, XXX)) {
				index = joggSyncState.buffer(bufferSize);
				buffer = joggSyncState.data;
				try {
					count = inputStream.read(buffer, index, bufferSize);
				} catch(Exception e) {
					printStackTrace(e);
					return null;
				}
				if (count == -1) {
					return returnBuffer;
				}

				joggSyncState.wrote(count);

				if(count == 0) {
					endOfStream(SET, true);
				}
			}

			return returnBuffer;
		}

		private synchronized boolean initialized(boolean action, boolean value) {
			if(action == SET)
				initialized = value;
			return initialized;
		}

		private synchronized boolean endOfStream(boolean action, boolean value) {
			if(action == SET)
				endOfStream = value;
			return endOfStream;
		}

		private static byte[] appendByteArrays(@Nullable byte[] arrayOne, @Nullable byte[] arrayTwo, int arrayTwoBytes) {
			byte[] newArray;
			int bytes = arrayTwoBytes;

			if (arrayTwo == null || arrayTwo.length == 0)
				bytes = 0;
			else if (arrayTwo.length < arrayTwoBytes)
				bytes = arrayTwo.length;

			if(arrayOne == null && (arrayTwo == null || bytes <= 0)) {
				return null;
			} else if(arrayOne == null) {
				newArray = new byte[bytes];
				System.arraycopy(arrayTwo, 0, newArray, 0, bytes);
			} else if(arrayTwo == null || bytes <= 0) {
				newArray = new byte[arrayOne.length];
				System.arraycopy(arrayOne, 0, newArray, 0, arrayOne.length);
			} else {
				newArray = new byte[arrayOne.length + bytes];
				System.arraycopy(arrayOne, 0, newArray, 0, arrayOne.length);
				System.arraycopy(arrayTwo, 0, newArray, arrayOne.length, bytes);
			}

			return newArray;
		}

		private static byte[] appendByteArrays(@Nullable byte[] arrayOne, @Nullable byte[] arrayTwo) {
			byte[] newArray;
			if(arrayOne == null && arrayTwo == null) {
				return null;
			} else if(arrayOne == null) {
				newArray = new byte[arrayTwo.length];
				System.arraycopy(arrayTwo, 0, newArray, 0, arrayTwo.length);
			} else if(arrayTwo == null) {
				newArray = new byte[arrayOne.length];
				System.arraycopy(arrayOne, 0, newArray, 0, arrayOne.length);
			} else {
				newArray = new byte[arrayOne.length + arrayTwo.length];
				System.arraycopy(arrayOne, 0, newArray, 0, arrayOne.length);
				System.arraycopy(arrayTwo, 0, newArray, arrayOne.length, arrayTwo.length);
			}

			return newArray;
		}

		private void errorMessage(String message) {
			logger.errorMessage("Radio.CodecJOrbisStream", message, 0);
		}

		private void message(String message) {
			logger.message(message, 0);
		}

		private void printStackTrace(Exception e) {
			logger.printStackTrace(e, 1);
		}
	}
}
