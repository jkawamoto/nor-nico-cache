/*
 *  Copyright (C) 2010 Junpei Kawamoto
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package nor.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nor.core.plugin.Plugin;
import nor.core.proxy.filter.FilterRegister;
import nor.core.proxy.filter.MessageHandler;
import nor.core.proxy.filter.MessageHandlerAdapter;
import nor.core.proxy.filter.ReadonlyPatternFilterAdapter;
import nor.core.proxy.filter.ResponseFilter;
import nor.core.proxy.filter.ResponseFilterAdapter;
import nor.core.proxy.filter.StoringToFileFilter;
import nor.core.proxy.filter.StoringToFileFilter.CloseEventListener;
import nor.http.HeaderName;
import nor.http.HttpHeader;
import nor.http.HttpRequest;
import nor.http.HttpResponse;
import nor.http.Status;
import nor.util.FixedSizeMap;
import nor.util.log.Logger;

public class NicoCacheNor extends Plugin{

	// http://smile-{xxxxx}.nicovideo.jp/smile?v={id}.{rand}
	private File dir;

	private final Map<String, String> titleMap = new FixedSizeMap<String, String>(20);

	private static final Logger LOGGER = Logger.getLogger(NicoCacheNor.class);

	//============================================================================
	//  Constant strings
	//============================================================================
	private static final String Folder = "folder";
	private static final String DefaultFolder = "./cache/nico";

	private static final String ServerName = "NicoCacheNor/0.1";
	private static final String MIMETemplate = "video/%s";

	private static final String TitleURLPattern = "/watch/\\w{2}(\\d+)";
	private static final String VideoURLPattern = "nicovideo\\.jp/smile\\?\\w+=([0-9]+)\\.(?:[0-9]+)(low)?";
	private static final String TitleMIMEPattern = "html";
	private static final String VideoMIMEPattern = "video/(.+)";

	private static final String FindTitlePattern = "<(?:title|TITLE)>(.*)-.*</(?:title|TITLE)>";

	private static final String FilenameTemplate = "sm%s%s-%s.%s";

	private static final String FilenamePattern = "sm%s-.*\\.(.+)";
	private static final String LowFilenamePattern = "sm%slow-.*\\.(.+)";

	private static final String ForbiddenCharacters = "\"|<|>|\\||\0|:|\\*|\\?|\\\\|&|/";

	//============================================================================
	//  Public methods
	//============================================================================
	@Override
	public void init(){

		if(!this.properties.containsKey(Folder)){

			this.properties.setProperty(Folder, DefaultFolder);

		}

		this.dir = new File(this.properties.getProperty(Folder));
		this.dir.mkdirs();

		StoringToFileFilter.deleteTemplaryFiles(this.dir);

	}

	@Override
	public ResponseFilter[] responseFilters() {

		return new ResponseFilter[]{

				// タイトル保存用
				new ResponseFilterAdapter(TitleURLPattern, TitleMIMEPattern){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister reg) {

						if(msg.getStatus() == Status.OK){

							reg.add(new ReadonlyPatternFilterAdapter(FindTitlePattern){

								@Override
								public void update(final MatchResult res) {

									final String id = url.group(1);
									final String title = res.group(1).replaceAll(ForbiddenCharacters, "");

									NicoCacheNor.this.titleMap.put(id, title);

								}

							});

						}

					}

				},

				// ビデオファイル保存用
				new ResponseFilterAdapter(VideoURLPattern, VideoMIMEPattern){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getStatus() == Status.OK){

							final HttpHeader header = msg.getHeader();
							if(!ServerName.equals(header.get(HeaderName.Server))){

								final String id = url.group(1);
								final String cond = url.group(2) != null ? url.group(2) : "";
								final String title = NicoCacheNor.this.titleMap.containsKey(id) ? NicoCacheNor.this.titleMap.get(id) : "";

								final String filename = String.format(FilenameTemplate, id, cond, title, cType.group(1));
								final File dest = new File(NicoCacheNor.this.dir, filename);
								if(!dest.exists()){

									try {

										final StoringToFileFilter f = new StoringToFileFilter(dest);
										if(url.group(2) == null){

											// 対象が通常画質の場合，低画質のキャッシュを削除
											f.addListener(new CloseEventListener() {

												@Override
												public void close(boolean succeeded) {

													if(succeeded){

														for(final File low : NicoCacheNor.this.findLowCaches(id)){

															low.delete();
															LOGGER.info("update", "Delete the cache; {0}", low);

														}

													}

												}

											});

										}
										register.add(f);

										LOGGER.info("update", "Store this video to {0}", dest);

									} catch (final IOException e) {

										e.printStackTrace();

									}

								}

							}

						}

					}

				}

		};

	}

	@Override
	public MessageHandler[] messageHandlers() {

		return new MessageHandler[]{

				new MessageHandlerAdapter(VideoURLPattern){

					@Override
					public HttpResponse doRequest(final HttpRequest request, final MatchResult m) {

						File src = null;
						final String id = m.group(1);
						final File[] caches = NicoCacheNor.this.findCaches(id);
						if(caches.length != 0){

							src = caches[0];

						}else{

							if(m.group(2) != null){

								final File[] lows = NicoCacheNor.this.findLowCaches(id);
								if(lows.length != 0){

									src = lows[0];

								}

							}

						}

						HttpResponse ret = null;
						if(src != null){

							final String name = src.getName();
							final String ext = name.substring(name.lastIndexOf(".") + 1);

							try {

								ret = request.createResponse(Status.OK, new FileInputStream(src));

								final HttpHeader header = ret.getHeader();
								header.set(HeaderName.ContentLength, Long.toString(src.length()));
								header.set(HeaderName.Server, ServerName);
								header.set(HeaderName.ContentType, String.format(MIMETemplate, ext));

								LOGGER.info("doRequest", "Return from the cache: {0}", src);


							} catch (final FileNotFoundException e) {

								LOGGER.warning("doRequest", e.toString());
								LOGGER.catched(Level.FINE, "doRequest", e);

							}

						}

						return ret;

					}

				}

		};

	}

	//============================================================================
	//  Private methods
	//============================================================================
	private File[] findCaches(final String id){

		final Pattern pat = Pattern.compile(String.format(FilenamePattern, id));
		return this.findIfMatches(pat);

	}


	private File[] findLowCaches(final String id){

		final Pattern pat = Pattern.compile(String.format(LowFilenamePattern, id));
		return this.findIfMatches(pat);

	}

	private File[] findIfMatches(final Pattern pat){

		return this.dir.listFiles(new FilenameFilter(){

			@Override
			public boolean accept(final File dir, final String name) {

				final Matcher m = pat.matcher(name);
				return m.matches();

			}

		});

	}

}
