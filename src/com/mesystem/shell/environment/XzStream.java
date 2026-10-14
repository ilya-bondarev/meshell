package com.mesystem.shell;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Decodes .xz streams with the LZMA2 filter (the format of Termux package payloads). Integrity comes from the package SHA-256. */
final class XzStream extends InputStream {
    private static final int LIMIT=1<<27;
    private final InputStream in;
    private long consumed;
    private int checkSize=-1;
    private boolean finished;
    // LZMA2 output of the current chunk.
    private byte[] out=new byte[0];
    private int outPos,outLen;
    // Block and chunk state.
    private boolean inBlock,needDictReset,needProps;
    private long blockStart;
    private final Lzma lzma=new Lzma();

    XzStream(InputStream in) { this.in=in; }

    private int next() throws IOException { int b=in.read(); if(b<0) throw new EOFException("Truncated xz data"); consumed++; return b; }
    private void fully(byte[] target) throws IOException { for(int i=0;i<target.length;i++) target[i]=(byte)next(); }
    private long varint(CRC32 crc) throws IOException {
        long value=0;
        for(int i=0;i<9;i++) { int b=next(); if(crc!=null) crc.update(b); value|=(long)(b&0x7F)<<(7*i); if((b&0x80)==0) return value; }
        throw new IOException("Invalid xz integer");
    }
    private static int le32(byte[] b,int at) { return (b[at]&255)|(b[at+1]&255)<<8|(b[at+2]&255)<<16|(b[at+3]&255)<<24; }

    private boolean streamHeader(boolean first) throws IOException {
        byte[] header=new byte[12];
        // Stream padding between concatenated streams is zero words.
        while(true) {
            int b=in.read(); if(b<0) { if(first) throw new EOFException("Empty xz data"); return false; }
            consumed++; header[0]=(byte)b;
            for(int i=1;i<4;i++) header[i]=(byte)next();
            if(first || header[0]!=0 || header[1]!=0 || header[2]!=0 || header[3]!=0) break;
        }
        for(int i=4;i<12;i++) header[i]=(byte)next();
        if((header[0]&255)!=0xFD || header[1]!='7' || header[2]!='z' || header[3]!='X' || header[4]!='Z' || header[5]!=0) throw new IOException("Not an xz stream");
        CRC32 crc=new CRC32(); crc.update(header,6,2);
        if(header[6]!=0 || (header[7]&0xF0)!=0 || (int)crc.getValue()!=le32(header,8)) throw new IOException("Unsupported xz stream flags");
        int check=header[7]&15; checkSize=check==0?0:4<<((check-1)/3);
        return true;
    }

    /** Reads the next block header, or the index and footer when the stream has no more blocks. */
    private boolean blockHeader() throws IOException {
        int size=next();
        if(size==0) { index(); return false; }
        byte[] header=new byte[(size+1)*4]; header[0]=(byte)size;
        for(int i=1;i<header.length;i++) header[i]=(byte)next();
        CRC32 crc=new CRC32(); crc.update(header,0,header.length-4);
        if((int)crc.getValue()!=le32(header,header.length-4)) throw new IOException("Damaged xz block header");
        int flags=header[1]&255; if((flags&0x3C)!=0 || (flags&3)!=0) throw new IOException("Unsupported xz filter chain");
        int at=2;
        long[] read=new long[1];
        if((flags&0x40)!=0) at=skipVarint(header,at);
        if((flags&0x80)!=0) at=skipVarint(header,at);
        at=readVarint(header,at,read); if(read[0]!=0x21) throw new IOException("Only LZMA2 xz data is supported");
        at=readVarint(header,at,read); if(read[0]!=1) throw new IOException("Invalid LZMA2 properties");
        int bits=header[at]&255; if(bits>40) throw new IOException("Invalid LZMA2 dictionary");
        long dictionary=bits==40?0xFFFFFFFFL:(2L|(bits&1))<<(bits/2+11);
        if(dictionary>LIMIT) throw new IOException("xz dictionary is too large");
        lzma.dictionary((int)dictionary);
        blockStart=consumed; inBlock=true; needDictReset=true; needProps=true;
        return true;
    }
    private static int skipVarint(byte[] b,int at) throws IOException {
        for(int i=0;i<9;i++) { if(at>=b.length-4) break; if((b[at++]&0x80)==0) return at; }
        throw new IOException("Invalid xz block header");
    }
    private static int readVarint(byte[] b,int at,long[] value) throws IOException {
        value[0]=0;
        for(int i=0;i<9;i++) { if(at>=b.length-4) break; int x=b[at++]&255; value[0]|=(long)(x&0x7F)<<(7*i); if((x&0x80)==0) return at; }
        throw new IOException("Invalid xz block header");
    }
    private void index() throws IOException {
        CRC32 crc=new CRC32(); crc.update(0); long start=consumed-1;
        long records=varint(crc);
        for(long i=0;i<records;i++) { varint(crc); varint(crc); }
        while((consumed-start)%4!=0) { int b=next(); if(b!=0) throw new IOException("Damaged xz index"); crc.update(b); }
        byte[] tail=new byte[4]; fully(tail);
        if((int)crc.getValue()!=le32(tail,0)) throw new IOException("Damaged xz index");
        byte[] footer=new byte[12]; fully(footer);
        if(footer[10]!='Y' || footer[11]!='Z') throw new IOException("Damaged xz footer");
    }
    private void endBlock() throws IOException {
        while((consumed-blockStart)%4!=0) if(next()!=0) throw new IOException("Damaged xz block padding");
        for(int i=0;i<checkSize;i++) next();
        inBlock=false;
    }

    /** Decodes the next LZMA2 chunk into {@link #out}; returns false at the end of all streams. */
    private boolean chunk() throws IOException {
        while(true) {
            if(finished) return false;
            if(checkSize<0) { streamHeader(true); continue; }
            if(!inBlock) {
                if(blockHeader()) continue;
                if(!streamHeader(false)) { finished=true; return false; }
                continue;
            }
            int control=next();
            if(control==0) { endBlock(); continue; }
            if(control==1 || control==2) {
                if(control==1) { lzma.resetDictionary(); needDictReset=false; }
                else if(needDictReset) throw new IOException("Damaged LZMA2 data");
                int size=((next()<<8)|next())+1; ensure(size);
                for(int i=0;i<size;i++) out[i]=(byte)next();
                lzma.copy(out,size); outPos=0; outLen=size; return true;
            }
            if(control<0x80) throw new IOException("Damaged LZMA2 data");
            int reset=(control>>5)&3;
            int size=((control&0x1F)<<16)+((next()<<8)|next())+1,packed=((next()<<8)|next())+1;
            if(reset==3) { lzma.resetDictionary(); needDictReset=false; }
            else if(needDictReset) throw new IOException("Damaged LZMA2 data");
            if(reset>=2) { lzma.properties(next()); needProps=false; }
            else if(needProps) throw new IOException("Damaged LZMA2 data");
            if(reset>=1) lzma.resetState();
            byte[] input=new byte[packed]; fully(input);
            ensure(size); lzma.decode(input,out,size); outPos=0; outLen=size; return true;
        }
    }
    private void ensure(int size) { if(out.length<size) out=new byte[Math.max(size,1<<16)]; }

    @Override public int read() throws IOException {
        byte[] one=new byte[1]; int n=read(one,0,1); return n<0?-1:one[0]&255;
    }
    @Override public int read(byte[] target,int offset,int length) throws IOException {
        if(length==0) return 0;
        while(outPos==outLen) if(!chunk()) return -1;
        int n=Math.min(length,outLen-outPos); System.arraycopy(out,outPos,target,offset,n); outPos+=n; return n;
    }
    @Override public void close() throws IOException { in.close(); }

    /** LZMA decoder state shared across the chunks of one LZMA2 block. */
    private static final class Lzma {
        private byte[] dict=new byte[0];
        private int size,pos,full;
        private long total;
        private int lc,lp,pb,state,rep0,rep1,rep2,rep3,pending;
        private short[] literal=new short[0x300];
        private final short[] isMatch=new short[12<<4],isRep=new short[12],isRepG0=new short[12],isRepG1=new short[12],isRepG2=new short[12],
            isRep0Long=new short[12<<4],posSlot=new short[4<<6],special=new short[115],align=new short[16];
        private final Length match=new Length(),rep=new Length();
        // Range decoder over the chunk's packed bytes.
        private byte[] in; private int at,range,code;

        void dictionary(int bytes) { if(dict.length<bytes) dict=new byte[bytes]; size=bytes; }
        void resetDictionary() { pos=0; full=0; total=0; }
        void properties(int props) throws IOException {
            if(props>224) throw new IOException("Invalid LZMA properties");
            pb=props/45; props%=45; lp=props/9; lc=props%9;
            if(lc+lp>4) throw new IOException("Invalid LZMA properties");
            int need=0x300<<(lc+lp); if(literal.length!=need) literal=new short[need];
        }
        void resetState() {
            state=0; rep0=rep1=rep2=rep3=0; pending=0;
            for(short[] p:new short[][]{literal,isMatch,isRep,isRepG0,isRepG1,isRepG2,isRep0Long,posSlot,special,align}) Arrays.fill(p,(short)1024);
            match.reset(); rep.reset();
        }
        void copy(byte[] data,int length) {
            for(int i=0;i<length;i++) put(data[i]);
        }
        private void put(byte b) { dict[pos]=b; if(++pos==size) pos=0; if(full<size) full++; total++; }
        private int get(int distance) { int i=pos-distance-1; if(i<0) i+=size; return dict[i]&255; }

        private int bit(short[] probs,int i) throws IOException {
            if(Integer.compareUnsigned(range,1<<24)<0) normalize();
            int p=probs[i],bound=(range>>>11)*p;
            if(Integer.compareUnsigned(code,bound)<0) { range=bound; probs[i]=(short)(p+((2048-p)>>>5)); return 0; }
            range-=bound; code-=bound; probs[i]=(short)(p-(p>>>5)); return 1;
        }
        private void normalize() throws IOException {
            if(at>=in.length) throw new IOException("Damaged LZMA data");
            range<<=8; code=(code<<8)|(in[at++]&255);
        }
        private int tree(short[] probs,int base,int bits) throws IOException {
            int m=1; for(int i=0;i<bits;i++) m=(m<<1)|bit(probs,base+m); return m-(1<<bits);
        }
        private int reverse(short[] probs,int base,int bits) throws IOException {
            int m=1,symbol=0; for(int i=0;i<bits;i++) { int b=bit(probs,base+m); m=(m<<1)|b; symbol|=b<<i; } return symbol;
        }
        private int direct(int bits) throws IOException {
            int result=0;
            for(int i=0;i<bits;i++) {
                if(Integer.compareUnsigned(range,1<<24)<0) normalize();
                range>>>=1; int bit=Integer.compareUnsigned(code,range)>=0?1:0; if(bit==1) code-=range; result=(result<<1)|bit;
            }
            return result;
        }

        void decode(byte[] packed,byte[] out,int length) throws IOException {
            in=packed; at=0;
            if(packed.length<5 || packed[0]!=0) throw new IOException("Damaged LZMA data");
            range=0xFFFFFFFF; code=0; at=1; for(int i=0;i<4;i++) code=(code<<8)|(packed[at++]&255);
            int n=0,posMask=(1<<pb)-1,lpMask=(1<<lp)-1;
            // A match may continue past the previous chunk's output limit.
            for(;pending>0 && n<length;pending--) { byte b=(byte)get(rep0); put(b); out[n++]=b; }
            while(n<length) {
                int posState=(int)total&posMask;
                if(bit(isMatch,(state<<4)+posState)==0) {
                    int prev=full==0?0:get(0),base=0x300*((((int)total&lpMask)<<lc)+(prev>>>(8-lc))),symbol=1;
                    if(state<7) while(symbol<0x100) symbol=(symbol<<1)|bit(literal,base+symbol);
                    else {
                        int matchByte=get(rep0)<<1,offset=0x100;
                        do {
                            int matchBit=matchByte&offset; matchByte<<=1;
                            if(bit(literal,base+offset+matchBit+symbol)==1) { symbol=(symbol<<1)|1; offset&=matchBit; }
                            else { symbol<<=1; offset&=~matchBit; }
                        } while(symbol<0x100);
                    }
                    byte b=(byte)symbol; put(b); out[n++]=b;
                    state=state<4?0:state<10?state-3:state-6;
                    continue;
                }
                int len;
                if(bit(isRep,state)==0) {
                    rep3=rep2; rep2=rep1; rep1=rep0;
                    len=match.decode(this,posState);
                    state=state<7?7:10;
                    int slot=tree(posSlot,Math.min(len,3)<<6,6);
                    if(slot<4) rep0=slot;
                    else {
                        int bits=(slot>>>1)-1; int distance=(2|(slot&1))<<bits;
                        if(slot<14) distance+=reverse(special,distance-slot-1,bits);
                        else { distance+=direct(bits-4)<<4; distance+=reverse(align,0,4); }
                        rep0=distance;
                    }
                    if(rep0==-1) throw new IOException("Unexpected LZMA end marker");
                } else {
                    if(bit(isRepG0,state)==0) {
                        if(bit(isRep0Long,(state<<4)+posState)==0) {
                            if(Integer.compareUnsigned(rep0,full)>=0) throw new IOException("Damaged LZMA data");
                            state=state<7?9:11; byte b=(byte)get(rep0); put(b); out[n++]=b; continue;
                        }
                    } else {
                        int distance;
                        if(bit(isRepG1,state)==0) distance=rep1;
                        else { if(bit(isRepG2,state)==0) distance=rep2; else { distance=rep3; rep3=rep2; } rep2=rep1; }
                        rep1=rep0; rep0=distance;
                    }
                    len=rep.decode(this,posState);
                    state=state<7?8:11;
                }
                if(Integer.compareUnsigned(rep0,full)>=0) throw new IOException("Damaged LZMA data");
                len+=2;
                int now=Math.min(len,length-n); pending=len-now;
                for(int i=0;i<now;i++) { byte b=(byte)get(rep0); put(b); out[n++]=b; }
            }
            // Bits are normalized before use, so the final byte of a chunk may still be pending.
            if(Integer.compareUnsigned(range,1<<24)<0) normalize();
            if(at!=packed.length || code!=0) throw new IOException("Damaged LZMA2 chunk size");
        }
    }

    private static final class Length {
        private final short[] choice=new short[2],low=new short[16<<3],mid=new short[16<<3],high=new short[256];
        void reset() { Arrays.fill(choice,(short)1024); Arrays.fill(low,(short)1024); Arrays.fill(mid,(short)1024); Arrays.fill(high,(short)1024); }
        /** Returns the match length minus two. */
        int decode(Lzma rc,int posState) throws IOException {
            if(rc.bit(choice,0)==0) return rc.tree(low,posState<<3,3);
            if(rc.bit(choice,1)==0) return 8+rc.tree(mid,(posState<<3),3);
            return 16+rc.tree(high,0,8);
        }
    }
}
