# Measures the audio stream (PID 0x45, Tapo type 0x90) in a Tapo TS file: PES count + bytes/s. Usage: python -I tools/audio_rate.py file.ts
# Result 2026-11-11: 8000 bytes/s on camera live + hub clip = G.711 A-law 8 kHz mono.
import sys
d=open(sys.argv[1],'rb').read(); n=0; pes=[]; cur=None
for i in range(0,len(d)-187,188):
    p=d[i:i+188]
    if p[0]!=0x47: continue
    pid=((p[1]&0x1f)<<8)|p[2]
    if pid!=0x45: continue
    pusi=p[1]&0x40; afc=(p[3]>>4)&3; o=4
    if afc&2: o+=1+p[4]
    pl=p[o:]
    if pusi:
        if cur is not None: pes.append(cur)
        hl=pl[8]; pts=((pl[9]>>1)&7)<<30|pl[10]<<22|(pl[11]>>1)<<15|pl[12]<<7|pl[13]>>1
        cur=[pts,len(pl)-9-hl]
    elif cur: cur[1]+=len(pl)
pes.append(cur)
span=(pes[-1][0]-pes[0][0])/90000
tot=sum(x[1] for x in pes[:-1])
print("pes",len(pes),"bytes/pes",pes[1][1],"span s",round(span,2),"bytes/s",round(tot/span))
