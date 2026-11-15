# RGraphX — Guia de testes e coleta de evidências

Este guia mostra como rodar cada teste do app, como capturar os gráficos do Android Profiler e quais prints e dados vão para o relatório final.

---

## 0. Antes de medir

1. **Use a variante `release`.** No Android Studio, abra **Build → Select Build Variant** e troque o módulo `app` para **release**. No build *debug* o C++ é compilado sem otimização (`-O0`) e os números saem distorcidos. O próprio app mostra `DEBUG` ou `RELEASE` no topo da tela.
2. **Prefira o celular real.** O emulador roda em x86_64 no computador, então os tempos dele não representam um celular. Se usar o emulador, diga isso no relatório.
3. **Padronize o ambiente:** feche os outros apps, mantenha a bateria acima de 50% e não rode os testes com o celular quente ou carregando.
4. Rode cada teste **pelo menos 3 vezes** e use a média.

---

## 1. Tela principal (Image Lab)

| Botão | O que faz |
|---|---|
| **CARREGAR** | Abre o seletor de fotos. A foto escolhida passa a ser a base de todos os filtros (o lado maior é limitado a 4096 px). |
| **GRAY C++** | Grayscale feito em C++ direto na memória do Bitmap (NDK, sem OpenCV). |
| **GRAY KT** | O mesmo Grayscale em Kotlin puro. Mostra o tempo do laço e o tempo de copiar os pixels para a JVM e de volta. |
| **CANNY** | Detecção de bordas com OpenCV (Gaussian Blur 5x5 + Canny 80/160). |
| **BENCH** | Benchmark Kotlin x C++: 1 rodada de aquecimento e 5 rodadas intercaladas, com média, mínimo e fator de diferença. |
| **CPU STR** | Laço de 50 milhões de ciclos em C++ (teste de CPU). |
| **RESET** | Volta a mostrar a imagem original. |
| **STRESS LAB ▶** | Abre a tela de Stress Test. |

O painel de telemetria mostra o tempo da última execução, a resolução e o **heap nativo** (memória do C++ e dos Bitmaps) e o **heap Java** (memória do Kotlin), atualizados a cada segundo.

**Evidência:** print do console depois do **BENCH**, com as linhas `[BEN]`.

---

## 2. Stress Lab

### 2.1 START: escalonamento de resolução

Roda Grayscale C++, Grayscale Kotlin e Canny em 7 resoluções:
640x480 → 1280x720 → 1920x1080 → 2560x1440 → 3840x2160 → 5472x3648 → **8000x6000 (48 MP)**.

- A imagem de teste é **gerada pelo próprio app**: ruído, anéis e grade, sempre igual (semente 42). Assim o teste é **reproduzível** e não depende de baixar fotos.
- O cronômetro **TEMPO DA ETAPA** mostra o tempo ao vivo de cada etapa.
- Em resoluções altas é **esperado** aparecer `Kotlin: sem memória na heap Java`. O filtro Kotlin precisa copiar a imagem inteira para um `IntArray` dentro da heap Java, que tem limite. O C++ trabalha direto na memória nativa e não sofre com isso. **Esse é um dos resultados mais fortes do projeto.** O limite da heap Java do aparelho aparece no console quando a tela abre.
- O botão **■ PARAR** interrompe o teste depois da etapa atual.

### 2.2 DATASET: fotos reais

Escolha várias fotos (as do Drive do dataset, ou fotos da câmera com 12 MP ou mais). Cada uma passa pelas mesmas medições, com o lado maior limitado a 8000 px. A coluna **STATUS** registra a foto que falhou e o motivo (por exemplo, falta de memória ao abrir).

### 2.3 MEMORY LEAK: vazamento proposital e correção

| Fase | O que acontece | O que o gráfico deve mostrar |
|---|---|---|
| **A: sem release** | 10 Mats de 1920x1080 (cerca de 7,9 MB cada) são alocadas no C++ e **nunca liberadas** | O heap nativo sobe em "escada" (cerca de +79 MB) |
| **Correção** | `releaseLeakedMats()` libera tudo | Queda brusca de volta ao início |
| **B: com release** | A mesma carga, mas cada Mat é liberada com `mat.release()` | Linha estável |

No fim, o console mostra o resumo: pico **sem** release × pico **com** release.

### 2.4 EXPORTAR CSV: a planilha

Gera um `.csv` com todas as medições da sessão. Você escolhe onde salvar (Downloads, Drive etc.).

- Está no formato do **Excel em português** (`;` como separador e vírgula decimal) e abre direto no Excel ou no Google Planilhas.
- Colunas: teste, imagem, largura, altura, megapixels, `cpp_grayscale_ms`, `kotlin_total_ms`, `kotlin_laco_ms`, `canny_ms`, `fator_kotlin_cpp`, picos de heap nativo e Java, status, build e dispositivo.
- Embaixo vem a tabela do memory leak (fase, iteração, heap nativo, memória presa).

**Gráficos sugeridos para o relatório:**
1. Linhas: **tempo (ms) × megapixels**, com uma linha para C++, uma para Kotlin e uma para Canny.
2. Barras: **fator Kotlin/C++** por resolução.
3. Linhas: **heap nativo × iteração**, comparando a fase A (sem release) com a fase B (com release).

---

## 3. Android Profiler: prints de CPU e RAM

1. Conecte o celular, selecione a variante **release** e abra o Profiler pela janela **Profiler** do Android Studio (**View → Tool Windows → Profiler**). Inicie o app por lá, com a opção de perfil de **baixo overhead** ou a visualização de **telemetria ao vivo**, conforme o nome na sua versão do Android Studio. O manifest já declara `<profileable>`, o que permite perfilar a build release.
2. **Memory leak:** com a linha do tempo de memória aberta, vá em **Stress Lab → MEMORY LEAK**. Tire print do gráfico mostrando a **escada da fase A**, a **queda da correção** e a **linha reta da fase B**. A categoria que sobe é a **Native**.
3. **Threads e CPU:** com a linha do tempo de CPU aberta, rode **START**. Tire print mostrando a thread de trabalho (`DefaultDispatcher-worker-…`) ocupada enquanto a **thread `main` fica livre**. É a prova de que as Coroutines tiram o processamento pesado da Main Thread e evitam ANR.
4. **Garbage Collector:** durante o START, o gráfico de memória mostra os eventos de GC (ícone de lixeira) entre as resoluções. O app pede uma coleta entre as etapas.

---

## 4. Checklist de evidências do relatório final

- [ ] Print da tela principal com a imagem processada (Grayscale e Canny)
- [ ] Print do console com o **BENCH** rodado na variante release
- [ ] Planilha CSV do **START**, com pelo menos 3 rodadas
- [ ] Planilha CSV do **DATASET**
- [ ] Print do Profiler: **memory leak** (fases A, correção e B)
- [ ] Print do Profiler: **CPU e threads** durante o START
- [ ] Gráficos feitos a partir do CSV (tempo × MP, fator Kotlin/C++, leak)
- [ ] Modelo do aparelho e versão do Android (estão no console e no CSV)
