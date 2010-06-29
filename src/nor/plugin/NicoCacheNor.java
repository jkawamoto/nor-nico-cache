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
import nor.http.HeaderName;
import nor.http.HttpHeader;
import nor.http.HttpRequest;
import nor.http.HttpResponse;
import nor.http.Status;
import nor.util.FixedSizeMap;
import nor.util.log.EasyLogger;

public class NicoCacheNor extends Plugin{

	// http://smile-{xxxxx}.nicovideo.jp/smile?v={id}.{rand}

	private final Pattern urlPat = Pattern.compile("nicovideo\\.jp/smile\\?\\w+=([0-9]+)\\.(?:[0-9]+)(low)?");
	private final Pattern cTypePat = Pattern.compile("video/(.*)");
	private File dir;

	private final Map<String, String> titleMap = new FixedSizeMap<String, String>(20);

	private final EasyLogger LOGGER = EasyLogger.getLogger(NicoCacheNor.class);

	private static final String Folder = "folder";
	private static final String DefaultFolder = "./cache/nico";

	private static final String ServerName = "NicoCacheNor/0.1";
	private static final String MIMETemplate = "video/%s";

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
				new ResponseFilterAdapter("/watch/\\w{2}(\\d+)", "html"){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister reg) {

						if(msg.getCode() == 200){

							reg.add(new ReadonlyPatternFilterAdapter("<(?:title|TITLE)>(.*)-.*</(?:title|TITLE)>"){

								@Override
								public void update(final MatchResult res) {

									final String id = url.group(1);
									final String title = res.group(1);

									titleMap.put(id, title);

								}

							});

						}

					}

				},

				// ビデオファイル保存用
				new ResponseFilterAdapter(this.urlPat, this.cTypePat){

					@Override
					public void update(final HttpResponse msg, final MatchResult url, final MatchResult cType, final FilterRegister register) {

						if(msg.getCode() == 200){

							final String id = url.group(1);
							final String cond = url.group(2) != null ? url.group(2) : "";
							final String title = titleMap.containsKey(id) ? titleMap.get(id) : "";

							final String filename = String.format("sm%s%s-%s.%s", id, cond, title, cType.group(1));
							final File dest = new File(NicoCacheNor.this.dir, filename);
							if(!dest.exists()){

								try {

									register.add(new StoringToFileFilter(dest));

									// TODO: DL対象が low でなく，かつ low が存在する場合それを削除

								} catch (final IOException e) {

									e.printStackTrace();

								}

							}

							final HttpHeader header = msg.getHeader();
							header.remove(HeaderName.ETag);
							header.remove(HeaderName.LastModified);
							header.set(HeaderName.CacheControl, "no-cache");

						}

					}

				}

		};

	}

	@Override
	public MessageHandler[] messageHandlers() {

		return new MessageHandler[]{

				new MessageHandlerAdapter(this.urlPat){

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

								NicoCacheNor.this.LOGGER.info("Returns from the cache: " + src.toString());


							} catch (final FileNotFoundException e) {

								e.printStackTrace();

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

		final Pattern pat = Pattern.compile(String.format("sm%s[^l][^o][^w]-.*\\.(.+)", id));
		return this.findIfMatches(pat);

	}


	private File[] findLowCaches(final String id){

		final Pattern pat = Pattern.compile(String.format("sm%slow-.*\\.(.+)", id));
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
