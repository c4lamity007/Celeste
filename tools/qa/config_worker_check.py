"""Compile the real ConfigLibrary against inert cloud/config boundaries; no game/network/auth calls."""
from pathlib import Path
import argparse, hashlib, json, subprocess
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--java-home',type=Path,required=True)
args=parser.parse_args()
stage=ROOT/'build/module-qa/owner-beta-20260907-reload-01/config-worker-check'
source=stage/'stubs'; fixture=stage/'fixture-classes'; tests=stage/'test-classes'
for p in (source,fixture,tests): p.mkdir(parents=True,exist_ok=True)
stubs={
'net/fabricmc/loader/api/FabricLoader.java':r'''package net.fabricmc.loader.api;
public final class FabricLoader {
    private static final FabricLoader INSTANCE=new FabricLoader();
    public static FabricLoader getInstance(){return INSTANCE;}
    public java.nio.file.Path getConfigDir(){return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"celeste-offline-config-fixture");}
}''',
'org/slf4j/Logger.java':r'''package org.slf4j; public interface Logger { default void error(String message,Object... args){} }''',
'org/slf4j/LoggerFactory.java':r'''package org.slf4j; public final class LoggerFactory { private LoggerFactory(){} }''',
'dev/c4lam/celeste/compat/RuntimeLogging.java':r'''package dev.c4lam.celeste.compat;
public final class RuntimeLogging { private RuntimeLogging(){} public static org.slf4j.Logger getLogger(String name){return new org.slf4j.Logger(){};} }''',
'dev/c4lam/celeste/config/ConfigProfileManager.java':r'''package dev.c4lam.celeste.config;
public final class ConfigProfileManager {
    public record ConfigEntry(String name,long modifiedMillis){}
    public static ConfigProfileManager getInstance(){throw new AssertionError("No profile IO in worker fixture");}
    public static String sanitize(String s){return s;}
    public String active(){return "fixture";}
    public void saveActive(){throw new AssertionError("No profile writes");}
    public byte[] exportImage(String s){return new byte[0];}
    public java.util.List<String> enabledModules(String s){return java.util.List.of();}
    public String importCloudImage(String s,byte[] b){throw new AssertionError("No profile writes");}
    public java.util.List<ConfigEntry> listEntries(){return java.util.List.of();}
}''',
'dev/c4lam/celeste/config/CloudConfigClient.java':r'''package dev.c4lam.celeste.config;
public final class CloudConfigClient {
    public record RemoteConfig(String id,String name,long updatedAt,java.util.List<String> modules){}
    public static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
    public static final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
    public static final java.util.concurrent.CountDownLatch interrupted=new java.util.concurrent.CountDownLatch(1);
    public static volatile boolean block;
    public static volatile Thread worker;
    java.util.List<RemoteConfig> list() throws Exception {
        worker=Thread.currentThread();calls.incrementAndGet();entered.countDown();
        if(block){
            boolean done=false;
            while(!done){
                try {done=release.await(5,java.util.concurrent.TimeUnit.SECONDS);if(!done)throw new AssertionError("fixture release timeout");}
                catch(InterruptedException expected){interrupted.countDown();}
            }
        }
        return java.util.List.of(new RemoteConfig("fixture","Original synthetic config",1,java.util.List.of()));
    }
    String publish(String name,byte[] bytes,java.util.List<String> modules){throw new AssertionError("No cloud writes");}
    byte[] download(String id){throw new AssertionError("No cloud downloads");}
}'''}
for name,text in stubs.items():
 p=source/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
javac=args.java_home/'bin/javac.exe';java=args.java_home/'bin/java.exe'
actual=ROOT/'src/main/java/dev/c4lam/celeste/config/ConfigLibrary.java'
commands=[
 [str(javac),'-Xlint:all','-Werror','--release','21','-encoding','UTF-8','-d',str(fixture),str(actual),*[str(source/n) for n in stubs]],
 [str(javac),'-Xlint:all','-Werror','--release','21','-encoding','UTF-8','-d',str(tests),str(ROOT/'tools/qa/ConfigLibraryLifecycleCheck.java')],
 [str(java),'-Xverify:all','-ea','-cp',str(tests),'ConfigLibraryLifecycleCheck',str(fixture)],
]
results=[]
for i,command in enumerate(commands):
 result=subprocess.run(command,capture_output=True,text=True,timeout=35,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
 (stage/f'{i}.log').write_text(result.stdout+result.stderr,encoding='utf-8')
 if result.returncode: raise SystemExit(f'Config worker check failed ({i}): '+result.stdout+result.stderr)
 results.append(result.stdout.strip())
report={'status':'passed','production_source_sha256':hashlib.sha256(actual.read_bytes()).hexdigest(),'result':results[-1],'inert_boundaries':list(stubs),'network':False,'game':False,'auth':False}
(stage/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
