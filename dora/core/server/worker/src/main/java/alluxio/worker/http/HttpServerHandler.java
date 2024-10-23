/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.http;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import alluxio.AlluxioURI;
import alluxio.PositionReader;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.URIStatus;
import alluxio.conf.Configuration;
import alluxio.exception.AlluxioException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.PageNotFoundException;
import alluxio.file.ByteArrayTargetBuffer;
import alluxio.file.ReadTargetBuffer;
import alluxio.grpc.ListStatusPOptions;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.network.protocol.databuffer.DataFileChannel;
import alluxio.util.FileSystemOptionsUtils;
import alluxio.worker.http.vo.WritePageResponseVO;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link HttpServerHandler} deals with HTTP requests received from Netty Channel.
 */
public class HttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {

  private static final Logger LOG = LoggerFactory.getLogger(HttpServerHandler.class);

  private final PagedService mPagedService;

  private final HttpLoadService mLoadService;

  private final FileSystemContext mFileSystemContext;

  private final FileSystem mFileSystem;

  /**
   * {@link HttpServerHandler} deals with HTTP requests received from Netty Channel.
   *
   * @param pagedService     the {@link PagedService} object provides page related RESTful API
   * @param fsContextFactory the factory for creating file system context
   */
  public HttpServerHandler(PagedService pagedService,
                           FileSystemContext.FileSystemContextFactory fsContextFactory) {
    mPagedService = pagedService;
    mFileSystemContext = fsContextFactory.create(Configuration.global());
    mFileSystem = FileSystem.Factory.create(mFileSystemContext);
    mLoadService = new HttpLoadService(mFileSystem);
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws PageNotFoundException {
    if (msg instanceof HttpRequest) {
      HttpRequest req = (HttpRequest) msg;
      HttpResponseContext responseContext = dispatch(req);
      HttpResponse response = responseContext.getHttpResponse();

      boolean keepAlive = HttpUtil.isKeepAlive(req);
      if (keepAlive) {
        if (!req.protocolVersion().isKeepAliveDefault()) {
          response.headers().set(CONNECTION, KEEP_ALIVE);
        }
      } else {
        // Tell the client we're going to close the connection.
        response.headers().set(CONNECTION, CLOSE);
      }

      ChannelFuture channelFuture;
      if (response instanceof FullHttpResponse) {
        channelFuture = ctx.write(response);
      } else {
        ctx.write(response);
        ctx.write(responseContext.getBuffer());
        channelFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
      }

      if (!keepAlive) {
        channelFuture.addListener(ChannelFutureListener.CLOSE);
      }
    }
  }

  private HttpResponseContext dispatch(HttpRequest httpRequest)
      throws PageNotFoundException {
    String requestUri = httpRequest.uri();
    // parse the request uri to get the parameters
    List<String> fields = HttpRequestUtil.extractFieldsFromHttpRequestUri(requestUri);
    HttpRequestUri httpRequestUri = HttpRequestUri.of(fields);

    switch (httpRequest.method().name()) {
      case "GET":
        return dispatchGetRequest(httpRequest, httpRequestUri);
      case "PUT":
      case "POST":
        return dispatchPostRequest(httpRequest, httpRequestUri);
      default:
        // TODO(JiamingMai): this should not happen, we should throw an exception here
        return null;
    }
  }

  private HttpResponseContext dispatchPostRequest(
      HttpRequest httpRequest, HttpRequestUri httpRequestUri) throws PageNotFoundException {
    // parse the URI and dispatch it to different methods
    switch (httpRequestUri.getMappingPath()) {
      case "file":
        return doWritePage(httpRequest, httpRequestUri);
      default:
        // TODO(JiamingMai): this should not happen, we should throw an exception here
        return null;
    }
  }

  private HttpResponseContext dispatchGetRequest(
      HttpRequest httpRequest, HttpRequestUri httpRequestUri) throws PageNotFoundException {
    // parse the URI and dispatch it to different methods
    switch (httpRequestUri.getMappingPath()) {
      case "file":
        return doGetPage(httpRequest, httpRequestUri);
      case "files":
        return doListFiles(httpRequest, httpRequestUri);
      case "info":
        return doGetFileStatus(httpRequest, httpRequestUri);
      case "load":
        return doLoad(httpRequest, httpRequestUri);
      case "health":
        return doHealthCheck(httpRequest, httpRequestUri);
      default:
        // TODO(JiamingMai): this should not happen, we should throw an exception here
        return null;
    }
  }

  private HttpResponseContext doHealthCheck(HttpRequest httpRequest,
                                            HttpRequestUri httpRequestUri) {
    FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
        Unpooled.wrappedBuffer("worker is active".getBytes()));
    response.headers()
        .set(CONTENT_TYPE, TEXT_PLAIN)
        .setInt(CONTENT_LENGTH, response.content().readableBytes());
    return new HttpResponseContext(response, null);
  }

  private HttpResponseContext doWritePage(HttpRequest httpRequest, HttpRequestUri httpRequestUri)
      throws PageNotFoundException {
    List<String> remainingFields = httpRequestUri.getRemainingFields();
    String fileId = remainingFields.get(0);
    long pageIndex = Long.parseLong(remainingFields.get(2));

    try {
      if (httpRequest instanceof FullHttpRequest) {
        FullHttpRequest fullRequest = (FullHttpRequest) httpRequest;
        ByteBuf content = fullRequest.content();
        boolean success = mPagedService.writePage(fileId, pageIndex, ByteBufUtil.getBytes(content));
        WritePageResponseVO writePageResponseVO = new WritePageResponseVO(success,
            success == false ? "Failed to write page" : "Page written successfully");
        String responseJson = new Gson().toJson(writePageResponseVO);
        FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
            Unpooled.wrappedBuffer(responseJson.getBytes()));
        response.headers()
            .set(CONTENT_TYPE, APPLICATION_JSON)
            .setInt(CONTENT_LENGTH, response.content().readableBytes());
        return new HttpResponseContext(response, null);
      }
    } catch (Exception e) {
      LOG.error("Failed to write page. fileId: {}, pageIndex: {}", fileId, pageIndex, e);
    }
    WritePageResponseVO writePageResponseVO =
        new WritePageResponseVO(false, "The HTTP request doesn't have body content");
    String responseJson = new Gson().toJson(writePageResponseVO);
    FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
        Unpooled.wrappedBuffer(responseJson.getBytes()));
    response.headers()
        .set(CONTENT_TYPE, APPLICATION_JSON)
        .setInt(CONTENT_LENGTH, response.content().readableBytes());
    return new HttpResponseContext(response, null);
  }

  private HttpResponseContext doGetPage(HttpRequest httpRequest, HttpRequestUri httpRequestUri)
      throws PageNotFoundException {
    List<String> remainingFields = httpRequestUri.getRemainingFields();
    String fileId = remainingFields.get(0);
    long pageIndex = Long.parseLong(remainingFields.get(2));

    FileRegion fileRegion;
    String offsetStr = httpRequestUri.getParameters().get("offset");
    String lengthStr = httpRequestUri.getParameters().get("length");
    long offset = 0;
    long length = mPagedService.getPageSize();
    if (offsetStr != null && !offsetStr.isEmpty()) {
      offset = Long.parseLong(offsetStr);
      if (lengthStr != null && !lengthStr.isEmpty()) {
        length = Long.parseLong(lengthStr);
      } else {
        length -= offset;
      }
    }
    MetricsSystem.meter(MetricKey.WORKER_HTTP_BYTES_REQUESTED.getName()).mark(length);

    // todo: We should get ufsPath from pageId, ufsPath should be pageId in Alluxio EE
    String ufsPath = "s3://ycy-alluxio-test/underFs/blocks_files/file_7.243897438049316MB.txt";
    AlluxioURI uri = new AlluxioURI(ufsPath);
    ReadTargetBuffer buffer;
    int readLength;
    try {
      PositionReader positionReader = mFileSystem.openPositionRead(uri);
      byte[] bytes = new byte[(int) length];
      buffer = new ByteArrayTargetBuffer(bytes, 0);
      readLength = positionReader.readInternal(offset, buffer, (int) length);
      if(readLength == -1) {
        throw new PageNotFoundException("page not found: fileId " + fileId + ", pageIndex " + pageIndex);
      }
    }catch (FileDoesNotExistException | IOException e){
      throw new PageNotFoundException(e.toString());
    }

    ByteBuf nettyBuffer = Unpooled.wrappedBuffer(buffer.byteBuffer());
    MetricsSystem.meter(MetricKey.WORKER_HTTP_BYTES_READ_CACHE.getName()).mark(length);
    HttpResponse response = new DefaultHttpResponse(httpRequest.protocolVersion(), OK);
    response.headers()
            .set(CONTENT_TYPE, TEXT_PLAIN)
            .setInt(CONTENT_LENGTH, readLength);
    HttpResponseContext httpResponseContext = new HttpResponseContext(response, null);
    httpResponseContext.setBuffer(nettyBuffer);
    return httpResponseContext;
  }

  private HttpResponseContext doListFiles(HttpRequest httpRequest, HttpRequestUri httpRequestUri) {
    String path = httpRequestUri.getParameters().get("path");
    path = handleReservedCharacters(path);
    ListStatusPOptions options = FileSystemOptionsUtils.listStatusDefaults(
        Configuration.global()).toBuilder().build();
    try {
      List<URIStatus> uriStatuses = mFileSystem.listStatus(new AlluxioURI(path), options);
      List<ResponseFileInfo> responseFileInfoList = new ArrayList<>();
      for (URIStatus uriStatus : uriStatuses) {
        String type = uriStatus.isFolder() ? "directory" : "file";
        ResponseFileInfo responseFileInfo = new ResponseFileInfo(type, uriStatus.getName(),
            uriStatus.getPath(), uriStatus.getUfsPath(), uriStatus.getLastModificationTimeMs(),
            uriStatus.getLength());
        responseFileInfoList.add(responseFileInfo);
      }
      // convert to JSON string
      String responseJson = new Gson().toJson(responseFileInfoList);
      // create HTTP response
      FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
          Unpooled.wrappedBuffer(responseJson.getBytes()));
      response.headers()
          .set(CONTENT_TYPE, APPLICATION_JSON)
          .setInt(CONTENT_LENGTH, response.content().readableBytes());
      return new HttpResponseContext(response, null);
    } catch (IOException | AlluxioException e) {
      LOG.error("Failed to list files of path {}", path, e);
      return null;
    }
  }

  private HttpResponseContext doGetFileStatus(
      HttpRequest httpRequest, HttpRequestUri httpRequestUri) {
    String path = httpRequestUri.getParameters().get("path");
    path = handleReservedCharacters(path);
    try {
      URIStatus uriStatus = mFileSystem.getStatus(new AlluxioURI(path));
      List<ResponseFileInfo> responseFileInfoList = new ArrayList<>();
      String type = uriStatus.isFolder() ? "directory" : "file";
      ResponseFileInfo responseFileInfo = new ResponseFileInfo(type, uriStatus.getName(),
          uriStatus.getPath(), uriStatus.getUfsPath(), uriStatus.getLastModificationTimeMs(),
          uriStatus.getLength());
      responseFileInfoList.add(responseFileInfo);
      // convert to JSON string
      String responseJson = new Gson().toJson(responseFileInfoList);
      // create HTTP response
      FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
          Unpooled.wrappedBuffer(responseJson.getBytes()));
      response.headers()
          .set(CONTENT_TYPE, APPLICATION_JSON)
          .setInt(CONTENT_LENGTH, response.content().readableBytes());
      return new HttpResponseContext(response, null);
    } catch (IOException | AlluxioException e) {
      LOG.error("Failed to list files of path {}", path, e);
      return null;
    }
  }

  private HttpResponseContext doLoad(HttpRequest httpRequest, HttpRequestUri httpRequestUri) {
    HttpLoadOptions.Builder builder = HttpLoadOptions.Builder.newBuilder();

    Map<String, String> parameters = httpRequestUri.getParameters();
    String opTypeStr = parameters.get("opType");
    if (opTypeStr != null && !opTypeStr.isEmpty()) {
      builder.setOpType(HttpLoadOptions.OpType.of(opTypeStr));
    }
    String partialListingStr = parameters.get("partialListing");
    if (partialListingStr != null && !partialListingStr.isEmpty()) {
      builder.setPartialListing(Boolean.parseBoolean(partialListingStr));
    }
    String verifyStr = parameters.get("verify");
    if (verifyStr != null && !verifyStr.isEmpty()) {
      builder.setVerify(Boolean.parseBoolean(verifyStr));
    }
    String bandwidthStr = parameters.get("bandwidth");
    if (bandwidthStr != null && !bandwidthStr.isEmpty()) {
      builder.setBandWidth(Long.parseLong(bandwidthStr));
    }
    String verboseStr = parameters.get("verbose");
    if (verboseStr != null && !verboseStr.isEmpty()) {
      builder.setVerbose(Boolean.parseBoolean(verboseStr));
    }
    String loadMetadataOnlyStr = parameters.get("loadMetadataOnly");
    if (loadMetadataOnlyStr != null && !loadMetadataOnlyStr.isEmpty()) {
      builder.setLoadMetadataOnly(Boolean.parseBoolean(loadMetadataOnlyStr));
    }
    String skipIfExistsStr = parameters.get("skipIfExists");
    if (skipIfExistsStr != null && !skipIfExistsStr.isEmpty()) {
      builder.setSkipIfExists(Boolean.parseBoolean(skipIfExistsStr));
    }
    String fileFilterRegxPattern = parameters.get("fileFilterRegx");
    if (fileFilterRegxPattern != null && !fileFilterRegxPattern.isEmpty()) {
      builder.setFileFilterRegx(Optional.of(fileFilterRegxPattern));
    }
    String progressFormatStr = parameters.get("progressFormat");
    if (progressFormatStr != null && !progressFormatStr.isEmpty()) {
      builder.setProgressFormat(progressFormatStr);
    }
    String path = parameters.get("path");
    path = handleReservedCharacters(path);

    String responseStr = mLoadService.load(new AlluxioURI(path), builder.build());

    FullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), OK,
        Unpooled.wrappedBuffer(responseStr.getBytes()));
    response.headers()
        .set(CONTENT_TYPE, TEXT_PLAIN)
        .setInt(CONTENT_LENGTH, response.content().readableBytes());
    return new HttpResponseContext(response, null);
  }

  private String handleReservedCharacters(String path) {
    path = path.replace("%2F", "/");
    path = path.replace("%3A", ":");
    path = path.replace("%3F", "?");
    return path;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    super.handlerRemoved(ctx);
    mFileSystem.close();
    mFileSystemContext.close();
  }

  private static final class Metrics {
    // Note that only counter/guage can be added here.
    // Both meter and timer need to be used inline
    // because new meter and timer will be created after {@link MetricsSystem.resetAllMetrics()}

    private static void registerGauges() {
      // Cache hit rate = Cache hits / (Cache hits + Cache misses).
      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.WORKER_HTTP_CACHE_HIT_RATE.getName()),
          () -> {
            long cacheHits = MetricsSystem.meter(
                MetricKey.WORKER_HTTP_BYTES_READ_CACHE.getName()).getCount();
            long total = MetricsSystem.meter(
                MetricKey.WORKER_HTTP_BYTES_REQUESTED.getName()).getCount();
            return cacheHits / (1.0 * total);
          });
    }
  }
}
