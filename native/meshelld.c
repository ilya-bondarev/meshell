#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/random.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include "sha256.h"

#define PORT 38765
#define REPLAY_SIZE (256 * 1024)
#define OUT_SIZE (REPLAY_SIZE + 32768)
#define INPUT_SIZE 65536
#define ROOT "/data/local/tmp/meshell"
#define HOME ROOT "/home"

static uint8_t key[32], replay[REPLAY_SIZE], output[OUT_SIZE], input[INPUT_SIZE+5], pending[INPUT_SIZE];
static size_t replay_start, replay_len, output_pos, output_len, input_len, pending_len;
static int client = -1, master = -1;
static pid_t child = -1;
static volatile sig_atomic_t stopping;
static pid_t workers[4];

static void stop_signal(int sig) { (void)sig; stopping = 1; }
static void fail(const char *message) { perror(message); exit(1); }
static void nonblock(int fd) { if (fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK)<0) fail("nonblock"); }

static int64_t milliseconds(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC,&now);
    return (int64_t)now.tv_sec*1000 + now.tv_nsec/1000000;
}

static int exact(int fd, void *buffer, size_t length, int writing, int64_t deadline) {
    uint8_t *p = buffer;
    while (length) {
        int64_t left = deadline-milliseconds();
        if (left<=0 || stopping) return -1;
        struct pollfd wait = { fd, writing?POLLOUT:POLLIN, 0 };
        int ready = poll(&wait,1,(int)left);
        if (ready<0 && errno==EINTR) continue;
        if (ready<=0) return -1;
        ssize_t n = writing ? send(fd,p,length,MSG_NOSIGNAL|MSG_DONTWAIT) : recv(fd,p,length,MSG_DONTWAIT);
        if (n<0 && errno==EAGAIN) continue;
        if (n < 0 && errno == EINTR && !stopping) continue;
        if (n <= 0) return -1;
        p += n; length -= (size_t)n;
    }
    return 0;
}

#include "exec.h"

static void child_signal(int sig) { (void)sig; }
static void reap_workers(void) {
    int status; pid_t pid;
    while((pid=waitpid(-1,&status,WNOHANG))>0)
        for(int i=0;i<4;i++) if(workers[i]==pid) workers[i]=0;
}

static void disconnect(void) {
    if (client >= 0) close(client);
    client = -1;
    output_len = output_pos = input_len = 0;
}

static int frame(char type, const void *data, size_t length) {
    if (client < 0) return -1;
    if (output_pos && output_len + length + 5 > OUT_SIZE) {
        memmove(output,output+output_pos,output_len-output_pos);
        output_len -= output_pos; output_pos = 0;
    }
    if (length > 65536 || output_len + length + 5 > OUT_SIZE) {
        disconnect(); return -1; /* Slow readers cannot block the phone's shell. */
    }
    uint32_t n = htonl((uint32_t)length);
    output[output_len++] = (uint8_t)type;
    memcpy(output+output_len,&n,4); output_len += 4;
    memcpy(output+output_len,data,length); output_len += length;
    return 0;
}

static void remember(const uint8_t *data, size_t length) {
    if (replay_len + length > REPLAY_SIZE) {
        size_t drop = replay_len + length - REPLAY_SIZE;
        replay_start = (replay_start+drop)%REPLAY_SIZE;
        replay_len -= drop;
    }
    size_t end = (replay_start+replay_len)%REPLAY_SIZE;
    size_t first = REPLAY_SIZE-end;
    if (first > length) first = length;
    memcpy(replay+end,data,first);
    memcpy(replay,data+first,length-first);
    replay_len += length;
}

static void set_size(unsigned rows, unsigned columns) {
    struct winsize size = { .ws_row=(unsigned short)rows, .ws_col=(unsigned short)columns };
    if (rows >= 2 && rows <= 500 && columns >= 10 && columns <= 500 && master >= 0)
        ioctl(master,TIOCSWINSZ,&size);
}

static void start_shell(void) {
    struct winsize size = { .ws_row=30, .ws_col=80 };
    child = forkpty(&master,NULL,NULL,&size);
    if (child < 0) fail("forkpty");
    if (child == 0) {
        signal(SIGPIPE,SIG_DFL); signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL);
        signal(SIGHUP,SIG_DFL); signal(SIGCHLD,SIG_DFL);
        /* Only stdio crosses the privilege boundary into commands. */
        for (int fd = 3; fd < 1024; ++fd) close(fd);
        shell_environment();
        setenv("SHELL","/system/bin/sh",1); setenv("TERM","xterm-256color",1);
        setenv("COLORTERM","truecolor",1); setenv("LANG","C.UTF-8",1);
        setenv("ENV",HOME "/.mkshrc",1); setenv("HISTFILE",HOME "/.history",1);
        setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
        if (chdir("/sdcard/MeShell") < 0 && chdir(HOME) < 0) _exit(126);
        execl("/system/bin/sh","sh","-i",(char *)NULL);
        _exit(127);
    }
    fcntl(master,F_SETFD,FD_CLOEXEC);
    nonblock(master);
    pending_len = replay_start = replay_len = 0;
}

static int handshake_fd=-1, handshake_state;
static size_t handshake_pos;
static int64_t handshake_deadline;
static uint8_t handshake_buffer[64], handshake_nonces[64];

/* Incremental handshake: an idle unauthenticated socket never stalls the PTY. */
static int authenticate(unsigned *rows, unsigned *columns) {
    size_t length=handshake_state==0?40:handshake_state==1?64:36;
    ssize_t n=handshake_state==1 ? send(handshake_fd,handshake_buffer+handshake_pos,length-handshake_pos,MSG_NOSIGNAL) :
        recv(handshake_fd,handshake_buffer+handshake_pos,length-handshake_pos,0);
    if(n<0 && (errno==EAGAIN || errno==EINTR)) return 0;
    if(n<=0) return -1;
    handshake_pos+=(size_t)n;
    if(handshake_pos<length) return 0;
    handshake_pos=0;
    if(handshake_state==0) {
        if(memcmp(handshake_buffer,"MESHL001",8)) return -1;
        memcpy(handshake_nonces,handshake_buffer+8,32);
        if(getrandom(handshake_nonces+32,32,0)!=32) return -1;
        memcpy(handshake_buffer,handshake_nonces+32,32);
        handshake_mac(key,"server",handshake_nonces,handshake_buffer+32);
        handshake_state=1; return 0;
    }
    if(handshake_state==1) { handshake_state=2; return 0; }
    uint8_t expected[32]; unsigned difference=0;
    handshake_mac(key,"client",handshake_nonces,expected);
    for(int i=0;i<32;i++) difference|=handshake_buffer[i]^expected[i];
    if(difference) return -1;
    *rows=(unsigned)handshake_buffer[32]*256+handshake_buffer[33];
    *columns=(unsigned)handshake_buffer[34]*256+handshake_buffer[35];
    if((*rows || *columns) && (*rows<2 || *rows>500 || *columns<10 || *columns>500)) return -1;
    return 1;
}

static void accept_client(int fd,unsigned rows,unsigned columns) {
    if(!rows && !columns) {
        reap_workers();
        for(int i=0;i<4;i++) if(!workers[i]) {
            pid_t pid=fork();
            if(pid==0) _exit(exec_worker(fd));
            if(pid>0) workers[i]=pid;
            close(fd); return;
        }
        close(fd); return;
    }
    disconnect();
    client = fd;
    int one = 1;
    setsockopt(fd,IPPROTO_TCP,TCP_NODELAY,&one,sizeof(one));
    nonblock(fd);
    if (master < 0) start_shell();
    set_size(rows,columns);
    char status[128];
    int n = snprintf(status,sizeof(status),"uid=%u | %s | PTY | pid=%d",
        (unsigned)geteuid(), geteuid()==0?"root":"shell", (int)child);
    frame('S',status,(size_t)n);
    frame('D',"\033c",2);
    size_t sent = 0;
    while (sent < replay_len && client >= 0) {
        size_t offset = (replay_start+sent)%REPLAY_SIZE;
        size_t count = REPLAY_SIZE-offset;
        if (count > replay_len-sent) count = replay_len-sent;
        if (count > 16384) count = 16384;
        frame('D',replay+offset,count);
        sent += count;
    }
}

static int parse_input(void) {
    size_t consumed = 0;
    while (input_len-consumed >= 5) {
        uint8_t *p = input+consumed;
        uint32_t length;
        memcpy(&length,p+1,4); length = ntohl(length);
        if (length > INPUT_SIZE || (p[0]!='D' && p[0]!='R')) return -1;
        if (input_len-consumed < length+5) break;
        if (p[0] == 'D') {
            if (pending_len+length > INPUT_SIZE) return -1;
            memcpy(pending+pending_len,p+5,length); pending_len += length;
        } else {
            if (length != 4) return -1;
            set_size((unsigned)p[5]*256+p[6],(unsigned)p[7]*256+p[8]);
        }
        consumed += length+5;
    }
    memmove(input,input+consumed,input_len-consumed); input_len -= consumed;
    return 0;
}

static void load_key(void) {
    int fd = open(ROOT "/token",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd < 0) fail("token");
    struct stat st;
    if (fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_uid!=geteuid() || (st.st_mode&0077)) {
        fprintf(stderr,"Token must be a private file owned by the broker UID\n"); exit(1);
    }
    char hex[65];
    if (read(fd,hex,sizeof(hex)) != 64) { fprintf(stderr,"Invalid token length\n"); exit(1); }
    close(fd);
    for (int i = 0; i < 32; ++i) {
        unsigned a=(unsigned)(hex[2*i]-'0'), b=(unsigned)(hex[2*i+1]-'0');
        if (a>9) a=(unsigned)(hex[2*i]-'a'+10);
        if (b>9) b=(unsigned)(hex[2*i+1]-'a'+10);
        if (a>15 || b>15) { fprintf(stderr,"Invalid token encoding\n"); exit(1); }
        key[i]=(uint8_t)(16*a+b);
    }
    memset(hex,0,sizeof(hex));
}

int main(int argc, char **argv) {
    if (argc != 2 || (strcmp(argv[1],"--daemon") && strcmp(argv[1],"--foreground"))) {
        fprintf(stderr,"Usage: meshelld --daemon|--foreground\n"); return 2;
    }
    if (geteuid()!=2000 && geteuid()!=0) {
        fprintf(stderr,"Start this broker through ADB shell or root\n"); return 1;
    }
    umask(0077);
    int lock = open(ROOT "/broker.lock",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
    if (lock < 0 || flock(lock,LOCK_EX|LOCK_NB)) fail("Broker already running / lock");
    load_key();
    int listener = socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0), one = 1;
    if (listener < 0) fail("socket");
    setsockopt(listener,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    struct sockaddr_in address = { .sin_family=AF_INET, .sin_port=htons(PORT),
        .sin_addr={ .s_addr=htonl(INADDR_LOOPBACK) } };
    if (bind(listener,(struct sockaddr *)&address,sizeof(address)) || listen(listener,4)) fail("listen");
    nonblock(listener);
    if (!strcmp(argv[1],"--daemon") && daemon(0,0)) fail("daemon");
    signal(SIGPIPE,SIG_IGN);
    struct sigaction child_action={ .sa_handler=child_signal };
    sigemptyset(&child_action.sa_mask); sigaction(SIGCHLD,&child_action,NULL);
    struct sigaction action = { .sa_handler=stop_signal };
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM,&action,NULL); sigaction(SIGINT,&action,NULL);
    sigaction(SIGHUP,&action,NULL);
    FILE *pidfile = fopen(ROOT "/broker.pid","w");
    if (!pidfile) fail("pid file");
    fprintf(pidfile,"%d\n",getpid()); fclose(pidfile);
    start_shell();
    while (!stopping) {
        reap_workers();
        struct pollfd fds[4] = {
            { listener,POLLIN,0 },
            { master,(short)(POLLIN|(pending_len?POLLOUT:0)),0 },
            { client,(short)(POLLIN|(output_len>output_pos?POLLOUT:0)),0 },
            { handshake_fd,handshake_state==1?POLLOUT:POLLIN,0 }
        };
        int ready = poll(fds,4,handshake_fd>=0?100:-1);
        if (ready < 0) { if (errno==EINTR) continue; break; }
        /* Process the old descriptors before accepting a replacement client. */
        if (client >= 0 && (fds[2].revents&(POLLERR|POLLHUP|POLLNVAL))) disconnect();
        if (client >= 0 && (fds[2].revents&POLLIN)) {
            ssize_t n=recv(client,input+input_len,sizeof(input)-input_len,0);
            if (n>0) { input_len+=(size_t)n; if (parse_input()) disconnect(); }
            else if (n==0 || (errno!=EAGAIN && errno!=EINTR)) disconnect();
        }
        if (client >= 0 && (fds[2].revents&POLLOUT)) {
            ssize_t n=send(client,output+output_pos,output_len-output_pos,MSG_NOSIGNAL);
            if (n>0) { output_pos+=(size_t)n; if (output_pos==output_len) output_pos=output_len=0; }
            else if (n<0 && errno!=EAGAIN && errno!=EINTR) disconnect();
        }
        if (master >= 0 && (fds[1].revents&POLLOUT) && pending_len) {
            ssize_t n=write(master,pending,pending_len);
            if (n>0) { memmove(pending,pending+n,pending_len-(size_t)n); pending_len-=(size_t)n; }
        }
        if (master >= 0 && (fds[1].revents&(POLLIN|POLLHUP|POLLERR))) {
            uint8_t buffer[8192];
            ssize_t n=read(master,buffer,sizeof(buffer));
            if (n>0) { remember(buffer,(size_t)n); frame('D',buffer,(size_t)n); }
            else if (n==0 || (n<0 && errno!=EAGAIN && errno!=EINTR)) {
                close(master); master=-1; pending_len=0;
                if (child>0) waitpid(child,NULL,WNOHANG);
                child=-1;
                frame('X',"Shell exited. Reconnect for a new session.",40);
            }
        }
        if(handshake_fd>=0) {
            unsigned rows=0,columns=0;
            int status=milliseconds()>=handshake_deadline?-1:0;
            if(!status && fds[3].revents) status=authenticate(&rows,&columns);
            if(status) {
                int fd=handshake_fd; handshake_fd=-1;
                if(status>0) accept_client(fd,rows,columns); else close(fd);
            }
        }
        if(fds[0].revents&POLLIN) {
            int fd=accept4(listener,NULL,NULL,SOCK_CLOEXEC|SOCK_NONBLOCK);
            if(fd>=0) {
                if(handshake_fd>=0) close(fd);
                else { handshake_fd=fd; handshake_state=0; handshake_pos=0; handshake_deadline=milliseconds()+2000; }
            }
        }
    }
    disconnect();
    if (master >= 0) {
        pid_t foreground=tcgetpgrp(master);
        if (foreground>0) kill(-foreground,SIGHUP);
        close(master);
    }
    if (child>0) { kill(-child,SIGHUP); kill(child,SIGHUP); }
    unlink(ROOT "/broker.pid");
    close(listener); close(lock);
    memset(key,0,sizeof(key));
    return 0;
}
