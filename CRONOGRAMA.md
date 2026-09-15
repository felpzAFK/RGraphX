Cronograma RGraphX (Setembro a Novembro / 2026)
Sprint 1: Fundação e Prova de Conceito (14/09 a 29/09)

O objetivo desta quinzena é ter um app feio, mas que não crasha ao processar uma foto.

    Felipe (DevOps/Front):

        Até 16/09: Fazer o Merge da configuração do Gabriel no GitHub e puxar para sua máquina.

        Até 22/09: Criar a casca (XML ou Compose) da tela "Image Lab" (só a imagem no centro e dois botões cegos).

    Gabriel (OpenCV):

        Até 20/09: Escrever um script nativo isolado que aplica um filtro básico (ex: tons de cinza) usando OpenCV.

        Até 25/09: Crucial: Implementar o comando mat.release() após o filtro para garantir que a RAM não exploda no primeiro teste.

    Alex (Design):

        Até 20/09: Ajustar o Figma para sair da vibe "editor genérico" e focar na pegada de laboratório/telemetria (fundo escuro, fontes monoespaçadas, cara de terminal).

    Arthur (Assets/Dataset):

        Até 18/09: Criar uma pasta no Drive e baixar 15 imagens em resoluções absurdas (4K, 8K) com muitas texturas (natureza, cidades) para os testes.

Sprint 2: Integração e Coroutines (30/09 a 15/10)

Aqui o Front-End e o Back-End se conectam. O app começa a funcionar de verdade.

    Felipe:

        Até 06/10: Conectar os botões cegos da tela às funções do Gabriel, encapsulando tudo em Kotlin Coroutines (Dispatchers.Default). A tela não pode congelar quando o filtro for aplicado!

    Gabriel:

        Até 08/10: Implementar o algoritmo pesado real: Canny Edge Detection (Detecção de Bordas).

        Até 12/10: Fazer o código calcular o tempo em milissegundos que o filtro levou e retornar isso pro Kotlin.

    Alex:

        Até 05/10: Desenhar a tela de Stress Test (um botão gigante de Start, barra de progresso e console de log).

    Arthur:

        Até 02/10: Extrair os ícones que o Alex usou no Figma e te entregar tudo em formato SVG mastigado.

        Até 10/10: Abrir o Word/Docs e criar a estrutura do relatório final (Capa, Objetivos, Metodologia) para não acumular pro fim do semestre.

Sprint 3: O Laboratório de Stress (16/10 a 05/11)

A fase de colher os dados para impressionar o professor.

    Felipe:

        Até 25/10: Construir a interface da tela do Stress Test e conectar o painel que mostra o tempo de processamento em tempo real.

    Gabriel:

        Até 28/10: Implementar a versão puramente em Kotlin do filtro (sem OpenCV) para termos a base de comparação de desempenho (o Benchmark).

    Alex:

        Até 30/10: Sentar com você para alinhar pequenos erros visuais (margin, padding, cor errada) que ficaram na passagem do Figma pro código.

    Arthur:

        Até 02/11: Pegar o arquivo .apk com você, instalar no celular, rodar todas as imagens do Drive que ele baixou e anotar numa planilha: qual imagem travou o celular, qual levou mais tempo, etc.

Sprint 4: Profiler e Preparação para a Defesa (06/11 a 27/11)

Fechamento. Código congelado, foco total nas evidências.

    Felipe & Gabriel:

        Até 15/11: Rodar o Android Profiler. Fazer o teste de tentar causar Memory Leak propositalmente e corrigir. Tirar prints dos gráficos de uso de CPU e RAM para provar o controle de threads.

    Arthur:

        Até 20/11: Pegar a planilha de testes dele + os gráficos do Profiler que vocês tiraram e jogar dentro da documentação final.

    Alex:

        Até 22/11: Montar os slides da apresentação final (baseado nos dados do Arthur).
