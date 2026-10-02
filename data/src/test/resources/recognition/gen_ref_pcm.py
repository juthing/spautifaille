import math, struct
import sys
# Génère ref_16k_mono_s16le.pcm (6 s, 16 kHz mono PCM 16 bits LE) : python3 gen_ref_pcm.py <sortie.pcm>
SR = 16000
DUR = 6
notes = [262, 330, 392, 523, 659, 784, 1047, 1319, 2093, 3136, 440, 880]
seed = 12345
out = []
for n in range(SR * DUR):
    t = n / SR
    seg = int(t / 0.25)
    t0 = (t % 0.25)
    env = math.exp(-t0 * 9)
    f1 = notes[seg % len(notes)]
    f2 = notes[(seg * 5 + 3) % len(notes)]
    f3 = notes[(seg * 7 + 1) % len(notes)] * 1.5
    vib = 1 + 0.004 * math.sin(2 * math.pi * 5 * t)
    v = 9000 * env * math.sin(2 * math.pi * f1 * vib * t)
    v += 6000 * env * math.sin(2 * math.pi * f2 * t)
    v += 3000 * math.exp(-t0 * 5) * math.sin(2 * math.pi * f3 * t)
    seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
    v += ((seed >> 8) % 801 - 400) * (1.0 + 4 * math.exp(-t0 * 30))
    out.append(max(-32768, min(32767, int(round(v)))))
open(sys.argv[1], 'wb').write(struct.pack('<%dh' % len(out), *out))
print(len(out))
