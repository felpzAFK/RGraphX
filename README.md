<p align="center">
  <img src="https://github.com/user-attachments/assets/d63f2bff-4a29-450f-977e-91f12c43501f" width="250" title="RGraphX Logo">
</p>

**RGraphX** é um laboratório de testes em formato de aplicativo mobile desenvolvido para a disciplina de **Código de Alta Performance Mobile**

O objetivo central deste projeto não é criar um produto comercial, mas sim **estressar o hardware do dispositivo (CPU e Memória RAM)** através  do processamento nativo de matrizes de imagem, mitigando gargalos computacionais e vazamentos de memória (*Memory Leaks*).

---

## Arquitetura do projeto

Para garantir que o processamento seja feito no limite do hardware, o projeto adota os seguintes pilares:

* **Proessamento 100% Offline:** Nenhuma dependência de APIs REST ou servidores na nuvem. O celular faz todo o trabalho bruto.
* **Integração Nativa (JNI/C++):** Utilização da biblioteca **OpenCV** para fugir das limitações da Máquina Virtual do Android e executar cálculos matemáticos em baixo nível.
* **Execução assíncrona com Kotlin Coroutines:** Operações computacionalmente intensivas são despachadas para threads de background (`Dispatchers.Default`), impedindo o bloqueio da Main Thread e reduzindo o risco de ANRs.
* **Avaliação Orientada a Méticas:** O Android Profiler é a ferramenta utilizada para validar o comportamento do aplicativo, cruzando os dados de performance com a telemetria exibida em tempo real na própria interface.

---

## Operações Analisadas e Testes de Laboratório

O projeto vai além da simples aplicação de filtros, atuando como um experimento mensurável através das seguintes frentes:

* **Benchmark Kotlin x C++:** Comparação direta de tempo de execução e consumo de recursos entre operações equivalentes implementadas nativamente e via OpenCV.
* **Stress Test & Escalonamento de Resolução:** Execução de testes progressivos com imagens variando de 1080p a resoluções de 8000x6000px, observando o comportamento da CPU, escalonamento de threads e limites do *Garbage Collector*.
* **Memory Leak Test:** Importação e troca massiva de *Assets* e objetos `Mat` na memória nativa para validar a liberação correta de RAM.

---

## Squad e funções

O desenvolvimento foi segmentado para garantir isolamento de contexto e máxima eficiência:
* **Felipe Ferreira:** Guardião do Repositório (DevOps) e Engenharia Front-End (Kotlin).
* **Alex Frazão:** Arquiteto Visual (Prototipagem no Figma) e Fluxo de Navegação.
* **Arthur Pujals:** Suporte Visual, Gestão de Assets de Alta Resolução e Curadoria do Dataset de testes.
* **Gabriel Camargo:** Engenheiro OpenCV (Integração C++, Scripts de processamento e gerenciamento de Threads).
