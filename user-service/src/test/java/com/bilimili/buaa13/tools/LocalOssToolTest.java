package com.bilimili.buaa13.tools;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class LocalOssToolTest {
 @TempDir Path dir;
 LocalOssTool tool;
 @BeforeEach void setup() {
  tool=new LocalOssTool();
  ReflectionTestUtils.setField(tool,"root",dir.resolve("media").toString());
  ReflectionTestUtils.setField(tool,"publicBase","/media/");
  ReflectionTestUtils.setField(tool,"chunks",dir.resolve("chunks").toString());
 }
 @Test void articleRoundTripAndBoundary() throws Exception {
  String text="# 本地文章\nUTF-8 持久化";
  String url=tool.uploadArticle(text);
  assertEquals(text,tool.readText(url));
  assertThrows(IllegalArgumentException.class,()->tool.readText("/media/../secret"));
  assertThrows(IllegalArgumentException.class,()->tool.readText("https://example.com/secret"));
  assertThrows(IllegalArgumentException.class,()->tool.deleteFiles("../"));
 }
 @Test void mergesChunksInOrderAndKeepsOtherUploads() throws Exception {
  Path chunks=Files.createDirectories(dir.resolve("chunks"));
  Files.writeString(chunks.resolve("video123-0"),"first");
  Files.writeString(chunks.resolve("video123-1"),"second");
  Files.writeString(chunks.resolve("other-0"),"other");
  String url=tool.appendUploadVideo("video123");
  assertEquals("firstsecond",tool.readText(url));
  assertFalse(Files.exists(chunks.resolve("video123-0")));
  assertEquals("other",Files.readString(chunks.resolve("other-0")));
  assertThrows(IllegalArgumentException.class,()->tool.appendUploadVideo("../bad"));
 }
}
