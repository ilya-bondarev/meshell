#ifndef MESHELL_EXEC_H
#define MESHELL_EXEC_H

/* Authenticated one-command workers. PTY remains attached to the terminal UI.
 * Request: E + u32 length, then five u32 values (timeout, cwd, command, stdin,
 * environment lengths), followed by those four byte strings. Environment uses
 * NUL-terminated NAME=value entries. Response: S, D*, X(exit, flags, bytes).
 */
#define EXEC_LIMIT (2*1024*1024)
#define EXEC_OUTPUT (512*1024)
#define TOOLUSR ROOT "/toolusr"

static int rpc_frame(int fd,char type,const void *bytes,size_t count) {
    uint8_t header[5]; uint32_t size=htonl((uint32_t)count);
    header[0]=(uint8_t)type; memcpy(header+1,&size,4);
    int64_t end=milliseconds()+10000;
    return exact(fd,header,5,1,end) || exact(fd,(void *)bytes,count,1,end);
}

static void shell_environment(void) {
    /* Android's Java command-line tools need the APEX boot classpath inherited
     * from adbd. Keep only OS runtime variables, not arbitrary caller secrets. */
    static const char *names[]={"BOOTCLASSPATH","DEX2OATBOOTCLASSPATH","SYSTEMSERVERCLASSPATH",
        "ANDROID_ART_ROOT","ANDROID_I18N_ROOT","ANDROID_TZDATA_ROOT","ANDROID_RUNTIME_ROOT",
        "ANDROID_STORAGE","EXTERNAL_STORAGE","ANDROID_ASSETS"};
    char *values[sizeof(names)/sizeof(names[0])]={0};
    for(size_t i=0;i<sizeof(names)/sizeof(names[0]);i++) {
        const char *value=getenv(names[i]); if(value) values[i]=strdup(value);
    }
    clearenv();
    for(size_t i=0;i<sizeof(names)/sizeof(names[0]);i++) if(values[i]) { setenv(names[i],values[i],1); free(values[i]); }
    setenv("HOME",HOME,1); setenv("TMPDIR",HOME "/tmp",1);
    setenv("PATH",HOME "/bin:" TOOLUSR "/bin:/system/bin:/system/xbin:/vendor/bin",1);
    setenv("JAVA_HOME",TOOLUSR "/lib/jvm/java-21-openjdk",1);
    setenv("PREFIX",TOOLUSR,1); setenv("LANG","C.UTF-8",1);
    setenv("SHELL","/system/bin/sh",1); setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
    setenv("SSL_CERT_FILE",TOOLUSR "/etc/tls/cert.pem",1);
    setenv("TERM","xterm-256color",1);
}

static int exec_worker(int fd) {
    signal(SIGCHLD,SIG_DFL); signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL); signal(SIGHUP,SIG_DFL);
    for(int i=0;i<1024;i++) if(i!=fd) close(i);
    char ready[64]; snprintf(ready,sizeof(ready),"uid=%u | exec-v1",(unsigned)geteuid());
    if(rpc_frame(fd,'S',ready,strlen(ready))) return 1;
    uint8_t head[5]; uint32_t length;
    if(exact(fd,head,5,0,milliseconds()+5000) || head[0]!='E') return 1;
    memcpy(&length,head+1,4); length=ntohl(length);
    if(length<20 || length>EXEC_LIMIT) return 1;
    uint8_t *body=calloc(1,(size_t)length+1);
    if(!body) return 1;
    if(exact(fd,body,length,0,milliseconds()+10000)) { free(body); return 1; }
    uint32_t fields[5]; memcpy(fields,body,20);
    uint64_t total=20;
    for(int i=0;i<5;i++) { fields[i]=ntohl(fields[i]); if(i) total+=fields[i]; }
    if(total!=length || fields[0]<100 || fields[0]>900000 || fields[1]>4096 || !fields[2]) { free(body); return 1; }
    char *cwd=strndup((char *)body+20,fields[1]);
    char *command=strndup((char *)body+20+fields[1],fields[2]);
    uint8_t *stdin_data=body+20+fields[1]+fields[2];
    char *environment=(char *)stdin_data+fields[3];
    if(!cwd || !command || strlen(cwd)!=fields[1] || strlen(command)!=fields[2] ||
       (fields[4] && environment[fields[4]-1]!=0)) { free(cwd); free(command); free(body); return 1; }
    int incoming[2],outgoing[2];
    if(pipe2(incoming,O_CLOEXEC) || pipe2(outgoing,O_CLOEXEC)) return 1;
    pid_t process=fork();
    if(process<0) return 1;
    if(process==0) {
        setpgid(0,0);
        dup2(incoming[0],0); dup2(outgoing[1],1); dup2(outgoing[1],2);
        /* pipe2 may return fd 0 after the worker closes its inherited stdio;
         * dup2(0,0) is a no-op and does not clear FD_CLOEXEC. */
        for(int i=0;i<3;i++) fcntl(i,F_SETFD,0);
        for(int i=3;i<1024;i++) close(i);
        signal(SIGPIPE,SIG_DFL); shell_environment();
        for(size_t at=0;at<fields[4];) {
            char *entry=environment+at; size_t n=strlen(entry); char *equal=strchr(entry,'=');
            if(!equal || equal==entry) _exit(125);
            *equal=0;
            for(char *p=entry;*p;p++) if(!((*p>='A'&&*p<='Z')||(*p>='a'&&*p<='z')||(*p>='0'&&*p<='9')||*p=='_')) _exit(125);
            setenv(entry,equal+1,1); at+=n+1;
        }
        if(chdir(*cwd?cwd:HOME)) { dprintf(2,"chdir: %s\n",strerror(errno)); _exit(125); }
        execl("/system/bin/sh","sh","-c",command,(char *)NULL); _exit(127);
    }
    setpgid(process,process);
    close(incoming[0]); close(outgoing[1]);
    nonblock(incoming[1]); nonblock(outgoing[0]);
    size_t written=0,reported=0; int flags=0,status=0,finished=0,eof=0,alive=1;
    int64_t deadline=milliseconds()+fields[0];
    while(!finished || !eof) {
        if(milliseconds()>=deadline) { flags|=1; kill(-process,SIGKILL); break; }
        if(incoming[1]>=0 && written==fields[3]) { close(incoming[1]); incoming[1]=-1; }
        struct pollfd fds[3]={{fd,POLLIN,0},{outgoing[0],POLLIN,0},{incoming[1],POLLOUT,0}};
        if(poll(fds,3,100)<0 && errno!=EINTR) break;
        if(fds[0].revents&(POLLIN|POLLHUP|POLLERR|POLLNVAL)) { alive=0; kill(-process,SIGKILL); break; }
        if(incoming[1]>=0 && (fds[2].revents&POLLOUT)) {
            ssize_t n=write(incoming[1],stdin_data+written,fields[3]-written);
            if(n>0) written+=(size_t)n;
            else if(n<0 && errno!=EAGAIN && errno!=EINTR) { close(incoming[1]); incoming[1]=-1; }
        }
        if(!eof && (fds[1].revents&(POLLIN|POLLHUP|POLLERR))) {
            uint8_t buffer[8192]; ssize_t n=read(outgoing[0],buffer,sizeof(buffer));
            if(n>0) {
                size_t send=(size_t)n;
                if(reported+send>EXEC_OUTPUT) { send=EXEC_OUTPUT-reported; flags|=2; }
                if(send && rpc_frame(fd,'D',buffer,send)) { alive=0; kill(-process,SIGKILL); break; }
                reported+=send;
            } else if(n==0 || (errno!=EAGAIN && errno!=EINTR)) { eof=1; close(outgoing[0]); outgoing[0]=-1; }
        }
        if(!finished && waitpid(process,&status,WNOHANG)==process) finished=1;
    }
    if(!finished) { kill(-process,SIGKILL); while(waitpid(process,&status,0)<0 && errno==EINTR) {} }
    /* A successful command may intentionally launch a background job with its
     * stdio redirected. Do not kill that job after its launcher has exited.
     * Inherited open pipes remain bounded by the deadline above. */
    int code=WIFEXITED(status)?WEXITSTATUS(status):128+(WIFSIGNALED(status)?WTERMSIG(status):0);
    if(flags&1) code=124;
    uint32_t result[3]={htonl((uint32_t)code),htonl((uint32_t)flags),htonl((uint32_t)reported)};
    if(alive) rpc_frame(fd,'X',result,sizeof(result));
    if(incoming[1]>=0) close(incoming[1]);
    if(outgoing[0]>=0) close(outgoing[0]);
    memset(body,0,length); free(body); free(cwd); free(command); close(fd);
    return 0;
}
#endif
