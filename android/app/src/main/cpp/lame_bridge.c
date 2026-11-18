#include <jni.h>
#include <stdio.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <lame.h>

JNIEXPORT jstring JNICALL Java_com_zdownloader_app_engine_LameEncoder_version(JNIEnv *env,jobject self) {
    return (*env)->NewStringUTF(env,get_lame_version());
}

/* Bounded local PCM chunks; cancellation never waits for a whole media file. */
static int io_error(void) { return errno == ENOSPC ? -3 : -4; }
static jint encode_impl(JNIEnv *env,jstring input,jstring output,jint bitrate,jint sampleRate,jint channels,jobject stopped) {
    if(!input || !output || !stopped) return -1;
    if(bitrate!=0 && (bitrate<32 || bitrate>320)) return -1;
    if(sampleRate!=44100 && sampleRate!=48000) return -1;
    if(channels!=1 && channels!=2) return -1;
    const char *src=(*env)->GetStringUTFChars(env,input,0);
    if(!src || (*env)->ExceptionCheck(env)) { if(src) (*env)->ReleaseStringUTFChars(env,input,src); (*env)->ExceptionClear(env); return -1; }
    const char *dst=(*env)->GetStringUTFChars(env,output,0);
    if(!dst || (*env)->ExceptionCheck(env)) { (*env)->ReleaseStringUTFChars(env,input,src); if(dst) (*env)->ReleaseStringUTFChars(env,output,dst); (*env)->ExceptionClear(env); return -1; }
    char srcCopy[1024]; char dstCopy[1024];
    if(snprintf(srcCopy,sizeof(srcCopy),"%s",src)>= (int)sizeof(srcCopy) || snprintf(dstCopy,sizeof(dstCopy),"%s",dst)>=(int)sizeof(dstCopy)) {
        (*env)->ReleaseStringUTFChars(env,input,src); (*env)->ReleaseStringUTFChars(env,output,dst); return -1;
    }
    (*env)->ReleaseStringUTFChars(env,input,src);
    (*env)->ReleaseStringUTFChars(env,output,dst);
    FILE *in=fopen(srcCopy,"rb"), *out=fopen(dstCopy,"wb+");
    lame_t encoder=lame_init();
    int result=-1;
    if(!in || !out) {result=io_error();goto done;}
    if(!encoder) goto done;
    if(fseek(in,0,SEEK_END)!=0) {result=-4;goto done;}
    long inputBytes=ftell(in);
    if(inputBytes<=0 || inputBytes % (sizeof(short)*channels)!=0) goto done;
    rewind(in);
    if(lame_set_in_samplerate(encoder,sampleRate)<0 || lame_set_out_samplerate(encoder,sampleRate)<0 || lame_set_num_channels(encoder,channels)<0 || lame_set_mode(encoder,channels==1 ? MONO : JOINT_STEREO)<0 || lame_set_quality(encoder,2)<0) goto done;
    if(bitrate==0) { if(lame_set_VBR(encoder,vbr_default)<0 || lame_set_VBR_quality(encoder,2)<0) goto done; }
    else { if(lame_set_VBR(encoder,vbr_off)<0 || lame_set_brate(encoder,bitrate)<0) goto done; }
    if(lame_init_params(encoder)<0) goto done;
    jclass cls=(*env)->GetObjectClass(env,stopped);
    if(!cls || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto done; }
    jmethodID get=(*env)->GetMethodID(env,cls,"get","()Z");
    if(!get || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); goto done; }
    if((*env)->CallBooleanMethod(env,stopped,get) || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result=-2; goto done; }
    {
    short pcm[8192]; unsigned char mp3[16384]; size_t count;
    size_t frame = (size_t)4096;
    size_t unit = sizeof(short) * (size_t)channels;
    while((count=fread(pcm,unit,frame,in))>0) {
        if((*env)->CallBooleanMethod(env,stopped,get) || (*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result=-2; goto done; }
        int bytes;
        if(channels==1) bytes=lame_encode_buffer(encoder,pcm,pcm,(int)count,mp3,sizeof(mp3));
        else bytes=lame_encode_buffer_interleaved(encoder,pcm,(int)count,mp3,sizeof(mp3));
        if(bytes<0) goto done;
        if(bytes>0 && fwrite(mp3,1,(size_t)bytes,out)!=(size_t)bytes) {result=io_error();goto done;}
    }
    if(ferror(in)) {result=-4;goto done;}
    int bytes=lame_encode_flush(encoder,mp3,sizeof(mp3));
    if(bytes<0) goto done;
    if(bytes>0 && fwrite(mp3,1,(size_t)bytes,out)!=(size_t)bytes) {result=io_error();goto done;}
    lame_mp3_tags_fid(encoder,out);
    result=ferror(out) || fflush(out)!=0 ? io_error() : 0;
    }
done:
    if(in) fclose(in);
    if(out) { if(fclose(out)!=0 && result==0) result=io_error(); }
    if(encoder) lame_close(encoder);
    if(result!=0) {
        // Never leave a partial MP3 masquerading as complete; caller also deletes on failure.
        remove(dstCopy);
    }
    return result;
}

JNIEXPORT jint JNICALL Java_com_zdownloader_app_engine_LameEncoder_encode(JNIEnv *env,jobject self,jstring input,jstring output,jint bitrate,jobject stopped) {
    return encode_impl(env,input,output,bitrate,44100,2,stopped);
}

JNIEXPORT jint JNICALL Java_com_zdownloader_app_engine_LameEncoder_encodeEx(JNIEnv *env,jobject self,jstring input,jstring output,jint bitrate,jint sampleRate,jint channels,jobject stopped) {
    return encode_impl(env,input,output,bitrate,sampleRate,channels,stopped);
}

/* A borrowed Android pipe descriptor is duplicated and polled. No PCM file,
 * shared native encoder handle, blocking FIFO open or unbounded read is needed. */
JNIEXPORT jint JNICALL Java_com_zdownloader_app_engine_LameEncoder_encodePipe(JNIEnv *env,jobject self,jint inputFd,jstring output,jint bitrate,jint sampleRate,jint channels,jobject stopped) {
    if(inputFd<0 || !output || !stopped || (bitrate!=0 && (bitrate<32 || bitrate>320)) ||
       (sampleRate!=44100 && sampleRate!=48000) || (channels!=1 && channels!=2)) return -1;
    const char *dst=(*env)->GetStringUTFChars(env,output,0);
    if(!dst || (*env)->ExceptionCheck(env)) {if(dst) (*env)->ReleaseStringUTFChars(env,output,dst);(*env)->ExceptionClear(env);return -1;}
    char path[1024];int length=snprintf(path,sizeof(path),"%s",dst);
    (*env)->ReleaseStringUTFChars(env,output,dst);
    if(length<0 || length>=(int)sizeof(path)) return -1;
    int in=-1,result=-1;FILE *out=NULL;lame_t encoder=NULL;
    struct stat info;
    if(fstat(inputFd,&info)!=0 || !S_ISFIFO(info.st_mode)) return -1;
    in=dup(inputFd);
    if(in<0) return io_error();
    int flags=fcntl(in,F_GETFL);
    if(flags<0 || fcntl(in,F_SETFL,flags|O_NONBLOCK)<0) {result=io_error();goto pipe_done;}
    out=fopen(path,"wb+");encoder=lame_init();
    if(!out) {result=io_error();goto pipe_done;}
    if(!encoder) goto pipe_done;
    if(lame_set_in_samplerate(encoder,sampleRate)<0 || lame_set_out_samplerate(encoder,sampleRate)<0 ||
       lame_set_num_channels(encoder,channels)<0 || lame_set_mode(encoder,channels==1?MONO:JOINT_STEREO)<0 || lame_set_quality(encoder,2)<0) goto pipe_done;
    if(bitrate==0) {if(lame_set_VBR(encoder,vbr_default)<0 || lame_set_VBR_quality(encoder,2)<0) goto pipe_done;}
    else {if(lame_set_VBR(encoder,vbr_off)<0 || lame_set_brate(encoder,bitrate)<0) goto pipe_done;}
    if(lame_init_params(encoder)<0) goto pipe_done;
    jclass cls=(*env)->GetObjectClass(env,stopped);
    if(!cls || (*env)->ExceptionCheck(env)) {(*env)->ExceptionClear(env);goto pipe_done;}
    jmethodID get=(*env)->GetMethodID(env,cls,"get","()Z");
    if(!get || (*env)->ExceptionCheck(env)) {(*env)->ExceptionClear(env);goto pipe_done;}
    {
        short pcm[8192];unsigned char mp3[16384];size_t carry=0;uint64_t frames=0;
        const size_t unit=sizeof(short)*(size_t)channels;
        for(;;) {
            if((*env)->CallBooleanMethod(env,stopped,get) || (*env)->ExceptionCheck(env)) {(*env)->ExceptionClear(env);result=-2;goto pipe_done;}
            struct pollfd wait={.fd=in,.events=POLLIN};
            int ready=poll(&wait,1,100);
            if(ready==0) continue;
            if(ready<0) {if(errno==EINTR) continue;result=io_error();goto pipe_done;}
            if(wait.revents&(POLLERR|POLLNVAL)) {result=-4;goto pipe_done;}
            ssize_t readBytes=read(in,((unsigned char*)pcm)+carry,4096*unit-carry);
            if(readBytes<0) {if(errno==EINTR || errno==EAGAIN) continue;result=io_error();goto pipe_done;}
            if(readBytes==0) break;
            size_t bytes=carry+(size_t)readBytes;size_t count=bytes/unit;carry=bytes%unit;
            if(count>0) {
                int encoded=channels==1?lame_encode_buffer(encoder,pcm,pcm,(int)count,mp3,sizeof(mp3)):
                    lame_encode_buffer_interleaved(encoder,pcm,(int)count,mp3,sizeof(mp3));
                if(encoded<0) goto pipe_done;
                if(encoded>0 && fwrite(mp3,1,(size_t)encoded,out)!=(size_t)encoded) {result=io_error();goto pipe_done;}
                frames+=count;
            }
            if(carry>0) memmove(pcm,((unsigned char*)pcm)+bytes-carry,carry);
        }
        if(frames==0 || carry!=0) goto pipe_done;
        if((*env)->CallBooleanMethod(env,stopped,get) || (*env)->ExceptionCheck(env)) {(*env)->ExceptionClear(env);result=-2;goto pipe_done;}
        int encoded=lame_encode_flush(encoder,mp3,sizeof(mp3));
        if(encoded<0) goto pipe_done;
        if(encoded>0 && fwrite(mp3,1,(size_t)encoded,out)!=(size_t)encoded) {result=io_error();goto pipe_done;}
        lame_mp3_tags_fid(encoder,out);
        result=ferror(out) || fflush(out)!=0 ? io_error():0;
    }
pipe_done:
    if(in>=0) close(in);
    if(out && fclose(out)!=0 && result==0) result=io_error();
    if(encoder) lame_close(encoder);
    if(result!=0) remove(path);
    return result;
}
