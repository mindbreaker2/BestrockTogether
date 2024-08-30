package com.example.bestrocktogether

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.experimental.and

data class Server(val ip: String, val port: Int)

class ServersAdapter(
    private val servers: MutableList<Server>,
    private val onServerClick: (Server) -> Unit,
    private val onDeleteClick: (Server) -> Unit
) : RecyclerView.Adapter<ServersAdapter.ServerViewHolder>() {

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val serverTextView: TextView = itemView.findViewById(R.id.serverTextView)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.serverTextView.text = "${server.ip}:${server.port}"

        holder.itemView.setOnClickListener {
            onServerClick(server)
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick(server)
            servers.removeAt(position)
            notifyItemRemoved(position)

            if (servers.isEmpty()) {
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemCount(): Int = servers.size
}

fun loadServers(context: Context): MutableList<Server> {
    val file = File(context.filesDir, "servers.json")
    return if (file.exists()) {
        val json = file.readText()
        val type = object : TypeToken<MutableList<Server>>() {}.type
        Gson().fromJson(json, type) ?: mutableListOf()
    } else {
        mutableListOf()
    }
}

fun saveServers(context: Context, servers: List<Server>) {
    val file = File(context.filesDir, "servers.json")
    val json = Gson().toJson(servers)
    file.writeText(json)
}

class MainActivity : AppCompatActivity() {

    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var startButton: Button
    private lateinit var testButton: Button
    private lateinit var testOpenConnection: Button
    private lateinit var statusTextView: TextView
    private lateinit var serversRecyclerView: RecyclerView
    private lateinit var serversAdapter: ServersAdapter

    private var isProxyRunning = false
    private var proxyJob: Job? = null
    private val dynamicPortSocketMap = mutableMapOf<Int, DatagramSocket>()
    private var serverSocket:DatagramSocket? = null
    private var clientSocket:DatagramSocket? = null
    private var pingPongSocket:DatagramSocket? = null


    private var lastClientAddress: InetAddress? = null
    private var lastClientPort: Int = 0
//a tester
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        startButton = findViewById(R.id.startButton)
        testButton = findViewById(R.id.testButton)
        testOpenConnection = findViewById(R.id.testOpenConnection)
        statusTextView = findViewById(R.id.statusTextView)
        serversRecyclerView = findViewById(R.id.serversRecyclerView)

        testOpenConnection.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val result = sendOpenConnectionRequest1()
                statusTextView.text = result
            }
        }

        testButton.setOnClickListener {
            testConnection()
        }

        portEditText.setText("19132")

        val serversList = loadServers(this)
        serversAdapter = ServersAdapter(serversList,
            onServerClick = { server ->
                ipEditText.setText(server.ip)
                portEditText.setText(server.port.toString())
            },
            onDeleteClick = { server ->
                serversList.remove(server)
                saveServers(this, serversList)
                serversAdapter.notifyDataSetChanged()
            }
        )

        serversRecyclerView.adapter = serversAdapter
        serversRecyclerView.layoutManager = LinearLayoutManager(this)

        startButton.setOnClickListener {
            val serverIp = ipEditText.text.toString()
            val serverPort = portEditText.text.toString().toIntOrNull()

            if (serverIp.isEmpty() || serverPort == null) {
                statusTextView.text = "Veuillez entrer une adresse IP et un port valides."
            } else {
                if (isProxyRunning) {
                    stopProxy()
                } else {
                    startProxy(serverIp, serverPort)
                    //juste un test d'envoi de ping
                    CoroutineScope(Dispatchers.Main).launch {
                        //pingServer("localhost",19132)
                        //FakePong("localhost",19132)
                    }
                    //fin du test

                    val newServer = Server(serverIp, serverPort)
                    if (!serversList.any { it.ip == serverIp && it.port == serverPort }) {
                        serversList.add(newServer)
                        saveServers(this, serversList)
                        serversAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun startProxy(serverIp: String, serverPort: Int) {
        isProxyRunning = true
        statusTextView.text = "Proxy démarré"
        startButton.text = "Arrêter le Proxy"

        proxyJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket = DatagramSocket(19132)
                serverSocket = DatagramSocket()

                val clientToServerJob = launch {
                    try {
                        while (isActive) {
                            val clientBuffer = ByteArray(4096)
                            val packetFromClient = DatagramPacket(clientBuffer, clientBuffer.size)
                            clientSocket?.receive(packetFromClient)
                            Log.d("Proxy", "Reçu du client : ${packetFromClient.length} octets de ${packetFromClient.address}:${packetFromClient.port}")

                            if (isPingPacket(packetFromClient.data)) {
                                Log.d("Proxy", "Paquet Ping détecté")
                            } else if (isPongPacket(packetFromClient.data)) {

                            }

                            val serverAddress = InetAddress.getByName(serverIp)
                            val packetToServer = DatagramPacket(packetFromClient.data, packetFromClient.length, serverAddress, serverPort)
                            serverSocket?.send(packetToServer)
                            Log.d("Proxy", "Envoyé au serveur : ${packetToServer.length} octets à ${serverIp}:${serverPort}")

                            lastClientAddress = packetFromClient.address
                            lastClientPort = packetFromClient.port
                        }
                    } catch (e: SocketException) {
                        Log.e("Proxy", "Erreur côté client -> serveur : ${e.message}")
                    }
                }

                val serverToClientJob = launch {
                    try {
                        while (isActive) {
                            val serverBuffer = ByteArray(4096)
                            val packetFromServer = DatagramPacket(serverBuffer, serverBuffer.size)
                            serverSocket?.receive(packetFromServer)
                            Log.d("Proxy", "Reçu du serveur : ${packetFromServer.length} octets de ${packetFromServer.address}:${packetFromServer.port}")

                            if (isPingPacket(packetFromServer.data)) {
                                Log.d("Proxy", "Paquet Ping détecté")
                            } else if (isPongPacket(packetFromServer.data)) {
                                Log.d("Pong", "paquet pong reçu du serveur")
                                handlePongPacket(packetFromServer)

                            }

                            if (lastClientAddress != null && lastClientPort != 0) {
                                val packetToClient = DatagramPacket(packetFromServer.data, packetFromServer.length, lastClientAddress, lastClientPort)
                                clientSocket?.send(packetToClient)
                                Log.d("Proxy", "Envoyé au client : ${packetToClient.length} octets à ${lastClientAddress}:${lastClientPort}")
                            }
                        }
                    } catch (e: SocketException) {
                        Log.e("Proxy", "Erreur côté serveur -> client : ${e.message}")
                    }
                }

                clientToServerJob.join()
                serverToClientJob.join()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Erreur dans la gestion du proxy : ${e.message}"
                }
            } finally {
                clientSocket?.close()
                serverSocket?.close()
            }
        }
    }



    private fun handlePingPacket(packetFromClient: DatagramPacket, serverIp: String, serverPort: Int) {
        val serverAddress = InetAddress.getByName(serverIp)
        val packetToServer = DatagramPacket(packetFromClient.data, packetFromClient.length, serverAddress, serverPort)
        serverSocket?.send(packetToServer)
        Log.d("Proxy", "Ping transféré au serveur : ${packetToServer.length} octets à ${serverIp}:${serverPort}")
    }

    private fun handlePongPacket(packetFromClient: DatagramPacket) {
        val data = packetFromClient.data
        val length = packetFromClient.length

        // Analyser et modifier le paquet Pong
        modifyPongPacketPorts(data)

        // Préparer et envoyer le paquet modifié au client
        val clientAddress = lastClientAddress ?: packetFromClient.address
        val clientPort = lastClientPort.takeIf { it != 0 } ?: packetFromClient.port
        val packetToClient = DatagramPacket(data, length, clientAddress, clientPort)
        pingPongSocket?.send(packetToClient)
        Log.d("Proxy", "Pong modifié transféré au client : ${packetToClient.length} octets à ${clientAddress}:${clientPort}")
    }

    // Fonction pour modifier les ports dans le paquet Pong
    private fun modifyPongPacketPorts(data: ByteArray) {
        Log.d("pong","paquet original : ${data.joinToString("") { it.toChar().toString() }}")
        try {
            // Représentation binaire du port 19132
            val newPortBytes = byteArrayOf(
                0b00110001, 0b00111001, 0b00110001, 0b00110011, 0b00110010
            )

            // Représentation binaire du point-virgule (;)
            val semicolonByte = 0b00111011.toByte()

            // Rechercher et modifier les ports en partant de la fin du paquet
            var modified = false
            var currentIndex = data.size - 1

            // Ignorez les 00000000 à la fin
            while (currentIndex >= 0 && data[currentIndex] == 0b00000000.toByte()) {
                currentIndex--
            }

            // Trouver les deux derniers points-virgules et remplacer les 5 octets précédents par le nouveau port
            repeat(2) {
                while (currentIndex >= 0 && data[currentIndex] != semicolonByte) {
                    currentIndex--
                }

                if (currentIndex >= 5) {
                    // Remplacer les 5 octets précédant le point-virgule
                    for (i in 0 until 5) {
                        data[currentIndex - 5 + i] = newPortBytes[i]
                    }
                    modified = true
                    currentIndex -= 6 // Passer le point-virgule et les 5 octets modifiés
                } else {
                    println("D/Pong: Erreur - Indice trop bas pour modifier le port.")
                    return
                }
            }

            if (modified) {
                // Log du paquet modifié pour vérification
                val modifiedPacketString = data.joinToString("") { it.toChar().toString() }
                println("D/Pong: paquet Pong modifié : $modifiedPacketString")
            } else {
                println("D/Pong: Aucun port trouvé pour modification.")
            }
        } catch (e: Exception) {
            println("D/Pong: Erreur lors de la modification des ports - ${e.message}")
        }
    }




    private fun handleOtherPackets(packetFromClient: DatagramPacket, serverIp: String, serverPort: Int) {
        val dynamicPort = (10000..65535).random()
        val dynamicSocket = DatagramSocket(dynamicPort)
        dynamicPortSocketMap[dynamicPort] = dynamicSocket
        val serverAddress = InetAddress.getByName(serverIp)
        val packetToServer = DatagramPacket(packetFromClient.data, packetFromClient.length, serverAddress, serverPort)
        dynamicSocket.send(packetToServer)
        Log.d("Proxy", "Paquet transféré au serveur : ${packetToServer.length} octets à ${serverIp}:${serverPort}")
    }

    private fun handleServerResponse(packetFromServer: DatagramPacket) {
        val dynamicPort = (10000..65535).random()
        val dynamicSocket = DatagramSocket(dynamicPort)
        dynamicPortSocketMap[dynamicPort] = dynamicSocket
        val clientAddress = lastClientAddress ?: packetFromServer.address
        val clientPort = lastClientPort.takeIf { it != 0 } ?: packetFromServer.port
        val packetToClient = DatagramPacket(packetFromServer.data, packetFromServer.length, clientAddress, clientPort)
        dynamicSocket.send(packetToClient)
        Log.d("Proxy", "Réponse du serveur transférée au client : ${packetToClient.length} octets à ${clientAddress}:${clientPort}")
    }

    private fun handleServerPackets(packetFromServer: DatagramPacket) {
        val clientAddress = lastClientAddress ?: packetFromServer.address
        val clientPort = lastClientPort.takeIf { it != 0 } ?: packetFromServer.port
        val packetToClient = DatagramPacket(packetFromServer.data, packetFromServer.length, clientAddress, clientPort)
        serverSocket?.send(packetToClient)
        Log.d("Proxy", "Paquet du serveur transféré au client : ${packetToClient.length} octets à ${clientAddress}:${clientPort}")
    }

    suspend fun pingServer(ip: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 5000

                val address = InetAddress.getByName(ip)

                // Construire le paquet de ping pour Bedrock
                // Définir la taille du message
                val magicId = byteArrayOf(
                    0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
                    0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78
                )

                val timestamp = System.currentTimeMillis()
                val timestampBytes = ByteArray(8)
                for (i in 0 until 8) {
                    timestampBytes[i] = (timestamp shr (56 - i * 8)).toByte()
                }

                // La taille totale du message : timestamp + magicId
                val message = ByteArray(1 + 8 + magicId.size)
                message[0] = 0x01 // Type du paquet "unconnected ping"

                System.arraycopy(timestampBytes, 0, message, 1, timestampBytes.size)
                System.arraycopy(magicId, 0, message, 1 + timestampBytes.size, magicId.size)

                // Créer et envoyer le paquet
                val packet = DatagramPacket(message, message.size, address, port)
                socket.send(packet)

                // Recevoir la réponse du serveur
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                // Traiter la réponse
                val response = responsePacket.data.copyOfRange(0, responsePacket.length)
                val responseString = String(response)

                socket.close()

                responseString
            } catch (e: Exception) {
                "Erreur: ${e::class.simpleName} - ${e.message}"
            }
        }
    }





    suspend fun FakePong(ip: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 5000

                val address = InetAddress.getByName(ip)

                // Construire le paquet de ping pour Bedrock
                // Définir la taille du message
                val magicId = byteArrayOf(
                    0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
                    0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78
                )

                val timestamp = System.currentTimeMillis()
                val timestampBytes = ByteArray(8)
                for (i in 0 until 8) {
                    timestampBytes[i] = (timestamp shr (56 - i * 8)).toByte()
                }

                // La taille totale du message : timestamp + magicId
                val message = ByteArray(1 + 8 + magicId.size)
                message[0] = 0x1c // Type du paquet "unconnected pong"

                System.arraycopy(timestampBytes, 0, message, 1, timestampBytes.size)
                System.arraycopy(magicId, 0, message, 1 + timestampBytes.size, magicId.size)

                // Créer et envoyer le paquet
                val packet = DatagramPacket(message, message.size, address, port)
                socket.send(packet)

                // Recevoir la réponse du serveur
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                // Traiter la réponse
                val response = responsePacket.data.copyOfRange(0, responsePacket.length)
                val responseString = String(response)

                socket.close()

                responseString
            } catch (e: Exception) {
                "Erreur: ${e::class.simpleName} - ${e.message}"
            }
        }
    }

    private fun testConnection() {
        val ip = ipEditText.text.toString()
        val port = portEditText.text.toString().toIntOrNull() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            val result = pingServer(ip, port)
            statusTextView.text = parseBedrockPingResponse(result)
            Log.d("reponse", result)
        }
    }

    private fun parseBedrockPingResponse(response: String): String {
        val data = response.split(";")
        return if (data.size >= 6) {
            """
        MOTD: ${data[1]}
        Version: ${data[3]}
        Joueurs: ${data[4]} / ${data[5]}
        """.trimIndent()
        } else {
            "Réponse invalide du serveur"
        }
    }


    private suspend fun sendOpenConnectionRequest1(): String {
        return withContext(Dispatchers.IO) {
            try {
                // Crée un socket sans spécifier de port pour avoir un port source aléatoire
                val socket = DatagramSocket()

                // Construction du paquet Open Connection Request 1
                val packetId: Byte = 0x05
                val mtuSize: Short = 1492
                val guid: Long = 0L
                val magicId = byteArrayOf(
                    0x00.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(),
                    0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
                    0xfd.toByte(), 0xfd.toByte(), 0x12.toByte(), 0x34.toByte(),
                    0x56.toByte(), 0x78.toByte()
                )

                val buffer = ByteBuffer.allocate(18 + magicId.size)
                buffer.put(packetId)
                buffer.put(magicId)
                buffer.putShort(mtuSize)
                buffer.putLong(guid)

                val packetData = buffer.array()

                // Définir l'adresse et le port de destination (localhost:19132)
                val address = InetAddress.getByName("localhost")
                val packet = DatagramPacket(packetData, packetData.size, address, 19132)

                // Envoyer le paquet depuis un port source aléatoire
                socket.send(packet)

                Log.d("Test", "Paquet 'Open Connection Request 1' envoyé à localhost:19132 depuis ${socket.localPort}")
                val port_envoi = socket.localPort

                socket.close()

                "Paquet 'Open Connection Request 1' envoyé avec succès à localhost:19132 depuis le port ${port_envoi}"
            } catch (e: Exception) {
                "Erreur lors de l'envoi du paquet : ${e.message}"
            }
        }
    }


    private fun stopProxy() {
        proxyJob?.cancel()
        isProxyRunning = false
        startButton.text = "Démarrer le Proxy"
        statusTextView.text = "Proxy arrêté"
        dynamicPortSocketMap.values.forEach { it.close() }
    }

    private fun isPingPacket(data: ByteArray): Boolean {
        // Un paquet Ping a généralement un identifiant spécifique (par exemple, 0x01)
        // et une structure particulière, mais la taille peut varier.
        return data.isNotEmpty() && data[0] == 0x01.toByte() && data.size >= 24
    }


    private fun isPongPacket(data: ByteArray): Boolean {
        return return data.isNotEmpty() && data.size >= 1 && data[0] == 0x1C.toByte() // Vérifiez l'identifiant du paquet Pong
    }
}
