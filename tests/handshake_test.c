#define main broker_main
#include "../native/meshelld.c"
#undef main
#include <assert.h>
int main(void) {
    int pair[2]; assert(socketpair(AF_UNIX,SOCK_STREAM,0,pair)==0);
    handshake_fd=pair[0]; nonblock(pair[0]); handshake_state=0; handshake_pos=0;
    unsigned rows=0,columns=0;
    int64_t start=milliseconds();
    assert(authenticate(&rows,&columns)==0);
    assert(milliseconds()-start<100);
    uint8_t hello[40]={0}; memcpy(hello,"MESHL001",8);
    assert(write(pair[1],hello,7)==7); assert(authenticate(&rows,&columns)==0);
    assert(write(pair[1],hello+7,33)==33); assert(authenticate(&rows,&columns)==0);
    assert(handshake_state==1); assert(authenticate(&rows,&columns)==0);
    uint8_t reply[64]; assert(read(pair[1],reply,64)==64);
    uint8_t proof[36]={0}; handshake_mac(key,"client",handshake_nonces,proof);
    proof[33]=30; proof[35]=80;
    assert(write(pair[1],proof,36)==36); assert(authenticate(&rows,&columns)==1);
    assert(rows==30 && columns==80);
    handshake_state=2; handshake_pos=0; proof[0]^=1;
    assert(write(pair[1],proof,36)==36); assert(authenticate(&rows,&columns)==-1);
    close(pair[0]); close(pair[1]);
    puts("HANDSHAKE_TEST_OK: idle, fragmented hello, valid proof, invalid proof");
    return 0;
}
